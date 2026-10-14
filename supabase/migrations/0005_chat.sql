-- gng 0005: chat overhaul. End-to-end encryption, DMs as conversations, read receipts,
-- tapback reactions, media, push tokens, mute, and voice/video calls.
-- Run in the Supabase SQL editor, after 0001 to 0004. Safe to run more than once.
--
-- Heads-up: after this, the server only accepts encrypted chat messages, so everyone needs the
-- new app. The first run also resets reactions. The plain-text DM tables from 0004 (dm_threads,
-- dm_messages, ...) are left untouched so nothing is lost, but the app no longer uses them.

------------------------------------------------------------------------------
-- DMs are conversations too: groups.kind = 'dm', exactly two members, never joinable by code
------------------------------------------------------------------------------

alter table public.groups
  add column if not exists kind   text not null default 'gang',
  add column if not exists dm_key text unique; -- "<smaller uuid>:<larger uuid>": one DM per pair
alter table public.groups drop constraint if exists groups_kind_check;
alter table public.groups add constraint groups_kind_check check (kind in ('gang', 'dm'));

-- Per-member mute. Members may change only this column of their own row
-- (roles change through set_member_role(), which runs with its own rights).
alter table public.group_members add column if not exists muted_until timestamptz;
revoke update on public.group_members from authenticated, anon;
grant update (muted_until) on public.group_members to authenticated;
drop policy if exists "mute own membership" on public.group_members;
create policy "mute own membership" on public.group_members for update
  using (user_id = auth.uid()) with check (user_id = auth.uid());

------------------------------------------------------------------------------
-- End-to-end encryption
--
-- Every phone has an X25519 identity key (HPKE). Each conversation has numbered key epochs:
-- a random AES-256 key, wrapped (HPKE) once per member's public key. Any member can start a
-- new epoch; phones do so whenever the membership or someone's key changes, so people who
-- left can't read new messages and new members can't read old ones.
-- The server only ever sees public keys, wrapped keys and ciphertext.
------------------------------------------------------------------------------

create table if not exists public.user_keys (
  user_id    uuid primary key default auth.uid() references public.profiles (id) on delete cascade,
  public_key text not null, -- base64 Tink public keyset
  key_id     text not null, -- first 16 hex chars of sha256(public_key)
  updated_at timestamptz not null default now()
);

create table if not exists public.conversation_keys (
  group_id         uuid not null references public.groups (id) on delete cascade,
  epoch            int  not null check (epoch > 0),
  recipient_id     uuid not null references public.profiles (id) on delete cascade,
  recipient_key_id text not null,
  wrapped_key      text not null,
  created_by       uuid not null references public.profiles (id) on delete cascade,
  created_at       timestamptz not null default now(),
  primary key (group_id, epoch, recipient_id)
);

alter table public.user_keys         enable row level security;
alter table public.conversation_keys enable row level security;

drop policy if exists "read own and group-mates keys" on public.user_keys;
create policy "read own and group-mates keys" on public.user_keys for select
  using (user_id = auth.uid() or public.shares_group(user_id));
drop policy if exists "publish own key" on public.user_keys;
create policy "publish own key" on public.user_keys for insert with check (user_id = auth.uid());
drop policy if exists "rotate own key" on public.user_keys;
create policy "rotate own key" on public.user_keys for update using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Members see who each epoch was wrapped for (to know when to rotate); a wrapped key is
-- useless to anyone but its recipient. Epochs are only written through publish_epoch().
drop policy if exists "members read epochs" on public.conversation_keys;
create policy "members read epochs" on public.conversation_keys for select using (public.is_member(group_id));

-- Publishes epoch N+1 atomically. p_wraps: [{"recipient_id", "recipient_key_id", "wrapped_key"}].
-- Returns false if someone else published that epoch first (the phone reloads and retries).
create or replace function public.publish_epoch(gid uuid, p_epoch int, p_wraps jsonb) returns boolean
language plpgsql security definer set search_path = public as $$
declare
  current_epoch int;
begin
  if not public.is_member(gid) then raise exception 'not a member'; end if;
  perform pg_advisory_xact_lock(hashtext(gid::text));
  select coalesce(max(epoch), 0) into current_epoch from conversation_keys where group_id = gid;
  if p_epoch <> current_epoch + 1 then return false; end if;
  insert into conversation_keys (group_id, epoch, recipient_id, recipient_key_id, wrapped_key, created_by)
  select gid, p_epoch, (w ->> 'recipient_id')::uuid, w ->> 'recipient_key_id', w ->> 'wrapped_key', auth.uid()
  from jsonb_array_elements(p_wraps) w
  where exists (select 1 from group_members m where m.group_id = gid and m.user_id = (w ->> 'recipient_id')::uuid);
  return true;
end;
$$;

------------------------------------------------------------------------------
-- Messages: what people write is ciphertext now. Server-made rows (system, plan, exam,
-- material, call) stay plaintext; they describe things the server already knows.
------------------------------------------------------------------------------

alter table public.messages
  alter column body drop not null,
  add column if not exists epoch      int,
  add column if not exists ciphertext text,
  add column if not exists client_id  uuid unique; -- lets the sender match the echo to its optimistic copy

alter table public.messages
  drop constraint if exists messages_body_check,
  drop constraint if exists messages_kind_check,
  drop constraint if exists messages_kind_valid,
  drop constraint if exists messages_body_length,
  drop constraint if exists messages_ciphertext_length,
  drop constraint if exists messages_content_check;
alter table public.messages
  add constraint messages_kind_valid check (kind in ('text', 'system', 'plan', 'exam', 'material', 'call')),
  add constraint messages_body_length check (body is null or char_length(body) between 1 and 2000),
  add constraint messages_ciphertext_length check (char_length(ciphertext) <= 24000),
  add constraint messages_content_check check (
    (kind = 'text' and (ciphertext is not null or body is not null)) or (kind <> 'text' and body is not null));

-- Still respects the gang's "who can send messages" setting from 0003.
drop policy if exists "members send text" on public.messages;
drop policy if exists "members send encrypted text" on public.messages;
create policy "members send encrypted text" on public.messages for insert
  with check (public.can_send_messages(group_id) and user_id = auth.uid() and kind = 'text'
              and body is null and ciphertext is not null and epoch is not null);

------------------------------------------------------------------------------
-- Reactions: one encrypted tapback per person per message, like iMessage.
-- Removing sets ciphertext to null (an UPDATE), because realtime can't filter DELETEs.
-- The old plaintext table is replaced the first time this runs.
------------------------------------------------------------------------------

do $$
begin
  if exists (select 1 from information_schema.columns
             where table_schema = 'public' and table_name = 'reactions' and column_name = 'emoji') then
    if exists (select 1 from pg_publication_tables
               where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'reactions') then
      alter publication supabase_realtime drop table public.reactions;
    end if;
    drop table public.reactions;
  end if;
end $$;

create table if not exists public.reactions (
  message_id bigint not null references public.messages (id) on delete cascade,
  user_id    uuid   not null default auth.uid() references public.profiles (id) on delete cascade,
  group_id   uuid   not null references public.groups (id) on delete cascade,
  epoch      int,
  ciphertext text check (char_length(ciphertext) <= 512),
  updated_at timestamptz not null default now(),
  primary key (message_id, user_id)
);
create index if not exists reactions_group on public.reactions (group_id);
alter table public.reactions enable row level security;

drop policy if exists "members read reactions" on public.reactions;
create policy "members read reactions" on public.reactions for select using (public.is_member(group_id));
drop policy if exists "react" on public.reactions;
create policy "react" on public.reactions for insert with check (
  user_id = auth.uid() and public.is_member(group_id)
  and exists (select 1 from public.messages m where m.id = message_id and m.group_id = reactions.group_id));
drop policy if exists "change reaction" on public.reactions;
create policy "change reaction" on public.reactions for update
  using (user_id = auth.uid()) with check (user_id = auth.uid() and public.is_member(group_id));

------------------------------------------------------------------------------
-- Read receipts: a delivered/read cursor per person per conversation.
-- Message ids only grow, so "read up to id N" covers every earlier message.
------------------------------------------------------------------------------

create table if not exists public.read_states (
  group_id     uuid   not null references public.groups (id) on delete cascade,
  user_id      uuid   not null references public.profiles (id) on delete cascade,
  delivered_id bigint not null default 0,
  read_id      bigint not null default 0,
  delivered_at timestamptz,
  read_at      timestamptz,
  primary key (group_id, user_id)
);
alter table public.read_states enable row level security;
drop policy if exists "members read receipts" on public.read_states;
create policy "members read receipts" on public.read_states for select using (public.is_member(group_id));

-- Cursors only move forward. Pass null for p_read to report delivery only
-- (what phones do when "Send read receipts" is off).
create or replace function public.mark_seen(gid uuid, p_delivered bigint, p_read bigint default null) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_member(gid) then return; end if;
  insert into read_states (group_id, user_id, delivered_id, read_id, delivered_at, read_at)
  values (gid, auth.uid(), greatest(p_delivered, coalesce(p_read, 0)), coalesce(p_read, 0), now(),
          case when p_read is not null then now() end)
  on conflict (group_id, user_id) do update set
    delivered_id = greatest(read_states.delivered_id, excluded.delivered_id),
    delivered_at = case when excluded.delivered_id > read_states.delivered_id then now() else read_states.delivered_at end,
    read_id      = greatest(read_states.read_id, excluded.read_id),
    read_at      = case when excluded.read_id > read_states.read_id then now() else read_states.read_at end;
end;
$$;

------------------------------------------------------------------------------
-- The home list: gangs and DMs with last activity and unread counts
------------------------------------------------------------------------------

drop function if exists public.my_conversations();
create function public.my_conversations()
returns table (
  id uuid, name text, emoji text, photo_url text, kind text, invite_code text, created_by uuid,
  other_user_id uuid, muted_until timestamptz,
  last_message_id bigint, last_message_at timestamptz, last_message_kind text,
  last_message_user_id uuid, last_message_body text, unread_count bigint
)
language sql stable security definer set search_path = public as $$
  select g.id, g.name, g.emoji, g.photo_url, g.kind, g.invite_code, g.created_by,
         (select o.user_id from group_members o where o.group_id = g.id and o.user_id <> auth.uid() limit 1),
         m.muted_until,
         last.id, coalesce(last.created_at, g.created_at), last.kind, last.user_id, last.body,
         (select count(*) from messages u
            where u.group_id = g.id and u.id > coalesce(rs.read_id, 0)
              and u.user_id is distinct from auth.uid())
  from group_members m
  join groups g on g.id = m.group_id
  left join read_states rs on rs.group_id = g.id and rs.user_id = auth.uid()
  left join lateral (
    select x.id, x.created_at, x.kind, x.user_id, x.body from messages x
    where x.group_id = g.id order by x.id desc limit 1
  ) last on true
  where m.user_id = auth.uid()
  order by coalesce(last.created_at, g.created_at) desc;
$$;

-- Opens (or reopens) the DM with someone you share a gang with.
-- (0004's open_dm() still exists for the old plain-text DM tables; the app no longer calls it.)
create or replace function public.open_direct_chat(p_other uuid) returns public.groups
language plpgsql security definer set search_path = public as $$
declare
  me uuid := auth.uid();
  k text;
  g public.groups;
begin
  if me is null then raise exception 'not signed in'; end if;
  if p_other = me then raise exception 'you can''t message yourself'; end if;
  if not public.shares_group(p_other) then raise exception 'you can only message people in your gangs'; end if;
  k := least(me::text, p_other::text) || ':' || greatest(me::text, p_other::text);
  select * into g from groups where dm_key = k;
  if g.id is null then
    insert into groups (name, emoji, invite_code, created_by, kind, dm_key)
    values ('Direct message', 'chat_bubble', 'DM' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 10)), me, 'dm', k)
    returning * into g;
  end if;
  insert into group_members (group_id, user_id) values (g.id, me), (g.id, p_other) on conflict do nothing;
  return g;
end;
$$;

-- Invite codes are for gangs only.
create or replace function public.join_group(p_code text)
returns public.groups
language plpgsql security definer set search_path = public as $$
declare
  g public.groups;
  who text;
begin
  if auth.uid() is null then raise exception 'not signed in'; end if;
  select * into g from groups where invite_code = upper(trim(p_code)) and kind = 'gang';
  if g.id is null then raise exception 'no group with that code'; end if;
  insert into group_members (group_id, user_id) values (g.id, auth.uid())
  on conflict do nothing;
  if found then
    select display_name into who from profiles where id = auth.uid();
    insert into messages (group_id, user_id, body, kind)
    values (g.id, null, coalesce(who, 'Someone') || ' joined the gang', 'system');
  end if;
  return g;
end;
$$;

------------------------------------------------------------------------------
-- Push notification tokens (FCM). The `push` Edge Function reads these with the service key.
------------------------------------------------------------------------------

create table if not exists public.device_tokens (
  token      text primary key,
  user_id    uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  platform   text not null default 'android',
  updated_at timestamptz not null default now()
);
alter table public.device_tokens enable row level security;
drop policy if exists "own tokens" on public.device_tokens;
create policy "own tokens" on public.device_tokens for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());

------------------------------------------------------------------------------
-- Calls. Media is peer-to-peer WebRTC (DTLS-SRTP); signaling goes over a private
-- Realtime channel "call:<id>" that only members of the call's conversation can use.
------------------------------------------------------------------------------

create table if not exists public.calls (
  id         uuid primary key default gen_random_uuid(),
  group_id   uuid not null references public.groups (id) on delete cascade,
  started_by uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  video      boolean not null default false,
  started_at timestamptz not null default now(),
  ended_at   timestamptz
);
create index if not exists calls_group on public.calls (group_id, started_at desc);
alter table public.calls enable row level security;
drop policy if exists "members read calls" on public.calls;
create policy "members read calls" on public.calls for select using (public.is_member(group_id));

create or replace function public.start_call(gid uuid, p_video boolean) returns public.calls
language plpgsql security definer set search_path = public as $$
declare
  c public.calls;
  who text;
begin
  if not public.is_member(gid) then raise exception 'not a member'; end if;
  -- Join the call already going here instead of starting a second one.
  select * into c from calls where group_id = gid and ended_at is null and started_at > now() - interval '3 hours'
  order by started_at desc limit 1;
  if c.id is not null then return c; end if;
  insert into calls (group_id, started_by, video) values (gid, auth.uid(), p_video) returning * into c;
  select display_name into who from profiles where id = auth.uid();
  insert into messages (group_id, user_id, body, kind, ref_id)
  values (gid, auth.uid(), coalesce(who, 'Someone') || ' started a ' ||
          case when p_video then 'video' else 'voice' end || ' call', 'call', c.id);
  return c;
end;
$$;

create or replace function public.end_call(p_call uuid) returns void
language plpgsql security definer set search_path = public as $$
declare
  c public.calls;
  mins int;
begin
  update calls set ended_at = now()
  where id = p_call and ended_at is null and public.is_member(group_id)
  returning * into c;
  if c.id is null then return; end if;
  mins := ceil(extract(epoch from (c.ended_at - c.started_at)) / 60.0);
  insert into messages (group_id, user_id, body, kind, ref_id)
  values (c.group_id, null, case when c.video then 'Video' else 'Voice' end || ' call ended · ' || mins || ' min', 'call', c.id);
end;
$$;

drop policy if exists "call members receive signals" on realtime.messages;
create policy "call members receive signals" on realtime.messages for select to authenticated using (
  realtime.topic() like 'call:%' and exists (
    select 1 from public.calls c
    where c.id::text = split_part(realtime.topic(), ':', 2) and public.is_member(c.group_id)));
drop policy if exists "call members send signals" on realtime.messages;
create policy "call members send signals" on realtime.messages for insert to authenticated with check (
  realtime.topic() like 'call:%' and exists (
    select 1 from public.calls c
    where c.id::text = split_part(realtime.topic(), ':', 2) and public.is_member(c.group_id)));

------------------------------------------------------------------------------
-- Chat media: private bucket of encrypted blobs under <group_id>/...
------------------------------------------------------------------------------

insert into storage.buckets (id, name, public, file_size_limit)
values ('chat-media', 'chat-media', false, 27262976) -- 26 MB: 25 MB files plus encryption overhead
on conflict (id) do nothing;

drop policy if exists "members read chat media" on storage.objects;
create policy "members read chat media" on storage.objects for select
  using (bucket_id = 'chat-media' and public.is_member_folder((storage.foldername(name))[1]));
drop policy if exists "members upload chat media" on storage.objects;
create policy "members upload chat media" on storage.objects for insert
  with check (bucket_id = 'chat-media' and public.is_member_folder((storage.foldername(name))[1]));
drop policy if exists "owners delete chat media" on storage.objects;
create policy "owners delete chat media" on storage.objects for delete
  using (bucket_id = 'chat-media' and owner = auth.uid());

------------------------------------------------------------------------------
-- Realtime
------------------------------------------------------------------------------

do $$
declare
  tbl text;
begin
  foreach tbl in array array['reactions', 'read_states', 'calls', 'conversation_keys'] loop
    if not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = tbl) then
      execute format('alter publication supabase_realtime add table public.%I', tbl);
    end if;
  end loop;
end $$;
