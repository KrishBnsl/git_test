// gng push: turns new messages and calls into FCM data messages.
//
// Triggered by Supabase Database Webhooks (see docs/SETUP.md) on:
//   messages INSERT, calls INSERT, calls UPDATE
// Pushes carry ids only. Message text is end-to-end encrypted, so the phone fetches
// and decrypts the message itself, then shows the notification.
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

const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
const serviceAccount = JSON.parse(Deno.env.get("FIREBASE_SERVICE_ACCOUNT") ?? "{}");

Deno.serve(async (req) => {
  if (req.headers.get("x-webhook-secret") !== Deno.env.get("WEBHOOK_SECRET")) {
    return new Response("forbidden", { status: 403 });
  }
  const payload = (await req.json()) as WebhookPayload;
  const r = payload.record;
  if (!r) return new Response("ignored");

  if (payload.table === "messages" && payload.type === "INSERT") {
    await notifyMembers(r.group_id as string, r.user_id as string | null, {
      type: "message",
      group_id: r.group_id as string,
      message_id: String(r.id),
    }, { respectMute: true });
  } else if (payload.table === "calls" && payload.type === "INSERT") {
    await notifyMembers(r.group_id as string, r.started_by as string, {
      type: "call",
      call_id: r.id as string,
      group_id: r.group_id as string,
      started_by: r.started_by as string,
      video: String(r.video),
      started_at: r.started_at as string,
    }, { respectMute: false, ttlSeconds: 45 });
  } else if (payload.table === "calls" && payload.type === "UPDATE" && r.ended_at && !payload.old_record?.ended_at) {
    await notifyMembers(r.group_id as string, null, { type: "call_end", call_id: r.id as string }, { respectMute: false, ttlSeconds: 60 });
  }
  return new Response("ok");
});

async function notifyMembers(
  groupId: string,
  exceptUser: string | null,
  data: Record<string, string>,
  opts: { respectMute: boolean; ttlSeconds?: number },
) {
  const { data: members, error } = await supabase
    .from("group_members")
    .select("user_id, muted_until")
    .eq("group_id", groupId);
  if (error || !members) return;
  const now = Date.now();
  const recipients = members
    .filter((m) => m.user_id !== exceptUser)
    .filter((m) => !opts.respectMute || !m.muted_until || Date.parse(m.muted_until) < now)
    .map((m) => m.user_id);
  if (recipients.length === 0) return;

  const { data: tokens } = await supabase.from("device_tokens").select("token").in("user_id", recipients);
  if (!tokens?.length) return;

  const accessToken = await googleAccessToken();
  await Promise.all(tokens.map(async ({ token }) => {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${serviceAccount.project_id}/messages:send`, {
      method: "POST",
      headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        message: {
          token,
          data,
          android: { priority: "HIGH", ...(opts.ttlSeconds ? { ttl: `${opts.ttlSeconds}s` } : {}) },
        },
      }),
    });
    // The app was uninstalled or the token rotated: forget it.
    if (res.status === 404 && (await res.text()).includes("UNREGISTERED")) {
      await supabase.from("device_tokens").delete().eq("token", token);
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
