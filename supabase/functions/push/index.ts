// gng push: turns new messages, reactions and calls into FCM data messages.
//
// Triggered by the database (migrations 0006 and 0008, via pg_net) on:
//   messages INSERT, reactions INSERT/UPDATE, calls INSERT/UPDATE, group_members INSERT, user_keys INSERT/UPDATE
// Message text is end-to-end encrypted, so the push carries the ciphertext (when it fits) and the
// phone decrypts it itself. Names and unread counts ride along so the phone can show the
// notification without waiting on any other request.
//
// Secrets (supabase secrets set ...):
//   FIREBASE_SERVICE_ACCOUNT  the Firebase service account JSON (Project settings -> Service accounts)
//   WEBHOOK_SECRET            any long random string; the webhook sends it in the x-webhook-secret header
// SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided automatically.

import { createClient } from "npm:@supabase/supabase-js@2";

type Row = Record<string, unknown>;
interface WebhookPayload {
  type: "INSERT" | "UPDATE" | "DELETE";
  table: string;
  record: Row | null;
  old_record: Row | null;
}
interface Recipient {
  user_id: string;
  token: string;
  muted_until: string | null;
  unread: number;
  unread_total: number;
  unread_chats: number;
}
interface Options {
  respectMute: boolean;
  ttlSeconds?: number;
  // People tagged in the message hear about it even if they muted the chat.
  alwaysNotify?: string[];
  // Only these people (e.g. the author of a message someone reacted to).
  only?: string[];
  // Silent housekeeping pushes don't need to wake the phone instantly.
  normalPriority?: boolean;
  collapseKey?: string;
}

const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
const serviceAccount = JSON.parse(Deno.env.get("FIREBASE_SERVICE_ACCOUNT") ?? "{}");
// FCM data payloads max out at 4 KB; leave room for the other fields.
const MAX_INLINE_CIPHERTEXT = 2800;

Deno.serve(async (req) => {
  if (req.headers.get("x-webhook-secret") !== Deno.env.get("WEBHOOK_SECRET")) {
    return new Response("forbidden", { status: 403 });
  }
  const payload = (await req.json()) as WebhookPayload;
  const r = payload.record;
  if (!r) return new Response("ignored");
  // Start fetching Google's access token while we look up who to notify.
  const token = googleAccessToken().catch(() => null);

  if (payload.table === "messages" && payload.type === "INSERT") {
    const groupId = r.group_id as string;
    const sender = r.user_id as string | null;
    const [names, recipients] = await Promise.all([names_(groupId, sender), recipients_(groupId, sender)]);
    const ciphertext = r.ciphertext as string | null;
    await send(token, recipients, {
      type: "message",
      group_id: groupId,
      message_id: String(r.id),
      kind: String(r.kind ?? "text"),
      created_at: String(r.created_at ?? ""),
      ...(sender ? { user_id: sender } : {}),
      ...(r.client_id ? { client_id: String(r.client_id) } : {}),
      ...(r.epoch != null ? { epoch: String(r.epoch) } : {}),
      ...(ciphertext && ciphertext.length <= MAX_INLINE_CIPHERTEXT ? { ciphertext } : {}),
      ...(r.body ? { body: String(r.body).slice(0, 500) } : {}),
      ...(r.ref_id ? { ref_id: String(r.ref_id) } : {}),
      ...(r.mentions ? { mentions: (r.mentions as string[]).join(",") } : {}),
      ...names,
    }, { respectMute: true, alwaysNotify: (r.mentions as string[] | null) ?? [] });
  } else if (payload.table === "reactions" && r.ciphertext) {
    // Only when the reaction is new or changed, and only to the person who wrote the message.
    if (payload.type === "UPDATE" && payload.old_record?.ciphertext === r.ciphertext) return new Response("ok");
    const groupId = r.group_id as string;
    const reactor = r.user_id as string;
    const { data: msg } = await supabase.from("messages").select("user_id").eq("id", r.message_id).maybeSingle();
    if (!msg?.user_id || msg.user_id === reactor) return new Response("ok");
    const [names, recipients] = await Promise.all([names_(groupId, reactor), recipients_(groupId, reactor)]);
    await send(token, recipients, {
      type: "reaction",
      group_id: groupId,
      message_id: String(r.message_id),
      user_id: reactor,
      epoch: String(r.epoch),
      ciphertext: r.ciphertext as string,
      ...names,
    }, { respectMute: true, only: [msg.user_id] });
  } else if (payload.table === "calls" && payload.type === "INSERT") {
    const groupId = r.group_id as string;
    const [names, recipients] = await Promise.all([
      names_(groupId, r.started_by as string),
      recipients_(groupId, r.started_by as string),
    ]);
    await send(token, recipients, {
      type: "call",
      call_id: r.id as string,
      group_id: groupId,
      started_by: r.started_by as string,
      video: String(r.video),
      started_at: r.started_at as string,
      ...names,
    }, { respectMute: false, ttlSeconds: 45 });
  } else if (payload.table === "calls" && payload.type === "UPDATE" && r.ended_at && !payload.old_record?.ended_at) {
    await send(token, await recipients_(r.group_id as string, null), { type: "call_end", call_id: r.id as string },
      { respectMute: false, ttlSeconds: 60 });
  } else if (payload.table === "group_members" && payload.type === "INSERT") {
    // Someone joined: whoever is online shares the chat's history keys with them.
    const groupId = r.group_id as string;
    await send(token, await recipients_(groupId, r.user_id as string), { type: "share_keys", group_id: groupId },
      { respectMute: false, normalPriority: true, collapseKey: "share_keys", ttlSeconds: 86400 });
  } else if (payload.table === "user_keys") {
    // Someone has a new phone (or reinstalled): everyone they chat with shares history keys again.
    if (payload.type === "UPDATE" && payload.old_record?.key_id === r.key_id) return new Response("ok");
    const { data: groups } = await supabase.from("group_members").select("group_id").eq("user_id", r.user_id);
    const lists = await Promise.all((groups ?? []).map((g) => recipients_(g.group_id as string, r.user_id as string)));
    const unique = [...new Map(lists.flat().map((x) => [x.token, x])).values()];
    await send(token, unique, { type: "share_keys" },
      { respectMute: false, normalPriority: true, collapseKey: "share_keys", ttlSeconds: 86400 });
  }
  return new Response("ok");
});

/** The conversation's name and kind, and the sender's display name. Not secret: the server stores them in plain. */
async function names_(groupId: string, senderId: string | null): Promise<Record<string, string>> {
  const [group, sender] = await Promise.all([
    supabase.from("groups").select("name, kind").eq("id", groupId).maybeSingle(),
    senderId
      ? supabase.from("profiles").select("display_name").eq("id", senderId).maybeSingle()
      : Promise.resolve({ data: null }),
  ]);
  return {
    ...(group.data ? { group_name: String(group.data.name), group_kind: String(group.data.kind ?? "gang") } : {}),
    ...(sender.data?.display_name ? { sender_name: String(sender.data.display_name) } : {}),
  };
}

async function recipients_(groupId: string, exceptUser: string | null): Promise<Recipient[]> {
  const { data, error } = await supabase.rpc("push_recipients", { p_group: groupId, p_except: exceptUser });
  if (error) console.error("push_recipients", error.message);
  return (data ?? []) as Recipient[];
}

async function send(accessToken: Promise<string | null>, recipients: Recipient[], data: Record<string, string>, opts: Options) {
  const now = Date.now();
  const targets = recipients
    .filter((m) => !opts.only || opts.only.includes(m.user_id))
    .filter((m) => !opts.respectMute || !m.muted_until || Date.parse(m.muted_until) < now ||
      (opts.alwaysNotify ?? []).includes(m.user_id));
  if (targets.length === 0) return;
  const bearer = await accessToken;
  if (!bearer) return;

  await Promise.all(targets.map(async (t) => {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${serviceAccount.project_id}/messages:send`, {
      method: "POST",
      headers: { Authorization: `Bearer ${bearer}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        message: {
          token: t.token,
          data: {
            ...data,
            // This person's unread counts, for "5 messages" and the "from 3 chats" summary.
            unread: String(t.unread ?? 0),
            unread_total: String(t.unread_total ?? 0),
            unread_chats: String(t.unread_chats ?? 0),
          },
          android: {
            priority: opts.normalPriority ? "NORMAL" : "HIGH",
            ...(opts.ttlSeconds ? { ttl: `${opts.ttlSeconds}s` } : {}),
            ...(opts.collapseKey ? { collapse_key: opts.collapseKey } : {}),
          },
        },
      }),
    });
    // The app was uninstalled or the token rotated: forget it.
    if (res.status === 404 && (await res.text()).includes("UNREGISTERED")) {
      await supabase.from("device_tokens").delete().eq("token", t.token);
    }
  }));
}

// --- Google OAuth for FCM HTTP v1, using the service account's private key ---------------

let cached: { token: string; expires: number } | null = null;

async function googleAccessToken(): Promise<string> {
  if (cached && cached.expires > Date.now() + 60_000) return cached.token;
  const now = Math.floor(Date.now() / 1000);
  const claims = {
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  };
  const jwt = await signJwt(claims, serviceAccount.private_key);
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: jwt }),
  });
  const json = await res.json();
  cached = { token: json.access_token, expires: Date.now() + json.expires_in * 1000 };
  return cached.token;
}

async function signJwt(claims: Record<string, unknown>, pem: string): Promise<string> {
  const b64url = (bytes: Uint8Array) =>
    btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  const enc = new TextEncoder();
  const header = b64url(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const body = b64url(enc.encode(JSON.stringify(claims)));
  const der = Uint8Array.from(
    atob(pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "")),
    (c) => c.charCodeAt(0),
  );
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${header}.${body}`)));
  return `${header}.${body}.${b64url(sig)}`;
}
