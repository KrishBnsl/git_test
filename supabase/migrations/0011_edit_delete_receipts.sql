-- gng 0011: edit and delete messages, and exact "Seen" / "Delivered" times.
-- Run after 0010. Safe to run more than once.

------------------------------------------------------------------------------
-- Edit (your own message, for 15 minutes) and delete for everyone (your own, for 2 days).
-- Both go through functions so the time limits and ownership can't be skipped. The new text is
-- encrypted on the phone like any message; deleting wipes the ciphertext from the server.
------------------------------------------------------------------------------

alter table public.messages
  add column if not exists edited_at  timestamptz,
  add column if not exists deleted_at timestamptz;

alter table public.messages drop constraint if exists messages_content_check;
alter table public.messages add constraint messages_content_check check (
  (kind = 'text' and (ciphertext is not null or body is not null or deleted_at is not null))
  or (kind <> 'text' and body is not null));

create or replace function public.edit_message(p_id bigint, p_epoch int, p_ciphertext text, p_mentions uuid[] default null)
returns timestamptz
language plpgsql security definer set search_path = public as $$
declare
  m public.messages;
  at timestamptz;
begin
  select * into m from messages where id = p_id;
  if m.id is null or m.user_id is distinct from auth.uid() or m.kind <> 'text' or m.deleted_at is not null then
    raise exception 'You can only edit your own messages';
  end if;
  if m.created_at < now() - interval '15 minutes' then raise exception 'Messages can only be edited for 15 minutes'; end if;
  if not exists (select 1 from conversation_keys k where k.group_id = m.group_id and k.epoch = p_epoch and k.recipient_id = auth.uid()) then
    raise exception 'unknown key';
  end if;
  if p_mentions is not null and exists (
    select 1 from unnest(p_mentions) t(id)
    where not exists (select 1 from group_members g where g.group_id = m.group_id and g.user_id = t.id)) then
    raise exception 'can only tag members';
  end if;
  update messages set epoch = p_epoch, ciphertext = p_ciphertext, mentions = p_mentions, edited_at = now()
  where id = p_id returning edited_at into at;
  return at;
end;
$$;

create or replace function public.delete_message_for_everyone(p_id bigint) returns void
language plpgsql security definer set search_path = public as $$
declare
  m public.messages;
begin
  select * into m from messages where id = p_id;
  if m.id is null or m.user_id is distinct from auth.uid() or m.kind <> 'text' then
    raise exception 'You can only delete your own messages';
  end if;
  if m.deleted_at is not null then return; end if;
  if m.created_at < now() - interval '2 days' then raise exception 'Messages can only be deleted for everyone for 2 days'; end if;
  update messages set ciphertext = null, epoch = null, mentions = null, deleted_at = now() where id = p_id;
  delete from reactions where message_id = p_id;
end;
$$;

-- So phones can catch up on edits and deletions they missed while offline.
create index if not exists messages_group_changed on public.messages (group_id, greatest(edited_at, deleted_at))
  where edited_at is not null or deleted_at is not null;

------------------------------------------------------------------------------
-- Exact receipt times. read_states only keeps each person's latest cursor, so we also log every
-- time it moves; "when did Aman see message 1234" is the first time their read cursor reached it.
------------------------------------------------------------------------------

create table if not exists public.read_marks (
  group_id uuid   not null references public.groups (id) on delete cascade,
  user_id  uuid   not null references public.profiles (id) on delete cascade,
  kind     text   not null check (kind in ('delivered', 'read')),
  upto     bigint not null,
  at       timestamptz not null default now(),
  primary key (group_id, user_id, kind, upto)
);
alter table public.read_marks enable row level security;
drop policy if exists "members read marks" on public.read_marks;
create policy "members read marks" on public.read_marks for select using (public.is_member(group_id));

create or replace function public.log_read_marks() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.delivered_id > coalesce(case when tg_op = 'UPDATE' then old.delivered_id end, 0) then
    insert into read_marks (group_id, user_id, kind, upto) values (new.group_id, new.user_id, 'delivered', new.delivered_id)
    on conflict do nothing;
  end if;
  if new.read_id > coalesce(case when tg_op = 'UPDATE' then old.read_id end, 0) then
    insert into read_marks (group_id, user_id, kind, upto) values (new.group_id, new.user_id, 'read', new.read_id)
    on conflict do nothing;
  end if;
  return null;
end;
$$;
drop trigger if exists read_states_marks on public.read_states;
create trigger read_states_marks after insert or update on public.read_states
  for each row execute function public.log_read_marks();

-- What we know from before this migration: each cursor as it stands now.
insert into public.read_marks (group_id, user_id, kind, upto, at)
select group_id, user_id, 'read', read_id, coalesce(read_at, now()) from public.read_states where read_id > 0
on conflict do nothing;
insert into public.read_marks (group_id, user_id, kind, upto, at)
select group_id, user_id, 'delivered', delivered_id, coalesce(delivered_at, now()) from public.read_states where delivered_id > 0
on conflict do nothing;

-- Who has received and seen one message, and when (null = not yet). Seen implies delivered.
create or replace function public.message_receipts(gid uuid, mid bigint)
returns table (user_id uuid, delivered_at timestamptz, read_at timestamptz)
language sql stable security definer set search_path = public as $$
  select m.user_id,
         (select min(r.at) from read_marks r where r.group_id = gid and r.user_id = m.user_id and r.upto >= mid),
         (select min(r.at) from read_marks r where r.group_id = gid and r.user_id = m.user_id and r.kind = 'read' and r.upto >= mid)
  from group_members m
  where m.group_id = gid and m.user_id <> auth.uid() and public.is_member(gid);
$$;
