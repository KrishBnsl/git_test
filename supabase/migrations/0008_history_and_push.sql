-- gng 0008: chat history for new members and new phones; faster, richer notifications.
-- Run after 0007. Safe to run more than once.
--
-- History: until now each key epoch was only wrapped for the people in the chat when it was made,
-- so someone who joined later (or reinstalled, which makes a new device key) saw old messages as
-- "can't decrypt". Now any member's phone that holds an old epoch key re-wraps it for current
-- members who lack it. The server still never sees a key: it only stores what phones wrapped.
--
-- Push: new members and new device keys wake the other members' phones (silently) so one of
-- them shares the history right away; reactions to your messages notify you; the push function
-- gets names and unread counts in one query so the phone can notify without any lookups.

------------------------------------------------------------------------------
-- Sharing old epoch keys
------------------------------------------------------------------------------

-- Every (conversation, epoch) I can open where some current member can't, with that member's
-- current public key, so my phone can wrap the epoch key for them.
create or replace function public.key_shares_needed()
returns table (group_id uuid, epoch int, recipient_id uuid, recipient_key_id text, public_key text)
language sql stable security definer set search_path = public as $$
  select mine.group_id, mine.epoch, gm.user_id, uk.key_id, uk.public_key
  from conversation_keys mine
  join user_keys me on me.user_id = auth.uid() and me.key_id = mine.recipient_key_id
  join group_members gm on gm.group_id = mine.group_id and gm.user_id <> auth.uid()
  join user_keys uk on uk.user_id = gm.user_id
  where mine.recipient_id = auth.uid()
    and exists (select 1 from group_members self where self.group_id = mine.group_id and self.user_id = auth.uid())
    and not exists (
      select 1 from conversation_keys theirs
      where theirs.group_id = mine.group_id and theirs.epoch = mine.epoch
        and theirs.recipient_id = gm.user_id and theirs.recipient_key_id = uk.key_id)
  order by mine.epoch desc
  limit 1000;
$$;

-- Stores keys a member re-wrapped for others. p_wraps: [{"epoch", "recipient_id", "recipient_key_id", "wrapped_key"}].
-- Only for epochs that exist, recipients who are members now, and their current key; never
-- replaces a wrap that already matches the recipient's current key. Returns how many were stored.
create or replace function public.share_epoch_keys(gid uuid, p_wraps jsonb) returns int
language plpgsql security definer set search_path = public as $$
declare
  stored int;
begin
  if not public.is_member(gid) then raise exception 'not a member'; end if;
  with incoming as (
    select (w ->> 'epoch')::int as epoch, (w ->> 'recipient_id')::uuid as recipient_id,
           w ->> 'recipient_key_id' as recipient_key_id, w ->> 'wrapped_key' as wrapped_key
    from jsonb_array_elements(p_wraps) w
  ), valid as (
    select i.* from incoming i
    join user_keys uk on uk.user_id = i.recipient_id and uk.key_id = i.recipient_key_id
    where exists (select 1 from group_members m where m.group_id = gid and m.user_id = i.recipient_id)
      and exists (select 1 from conversation_keys k where k.group_id = gid and k.epoch = i.epoch)
      and char_length(i.wrapped_key) <= 4000
  ), written as (
    insert into conversation_keys (group_id, epoch, recipient_id, recipient_key_id, wrapped_key, created_by)
    select gid, v.epoch, v.recipient_id, v.recipient_key_id, v.wrapped_key, auth.uid() from valid v
    on conflict (group_id, epoch, recipient_id) do update
      set recipient_key_id = excluded.recipient_key_id, wrapped_key = excluded.wrapped_key, created_by = excluded.created_by
      where conversation_keys.recipient_key_id <> excluded.recipient_key_id
    returning 1
  )
  select count(*) into stored from written;
  return stored;
end;
$$;

------------------------------------------------------------------------------
-- Push: who to notify, with names and unread counts (for the `push` Edge Function only)
------------------------------------------------------------------------------

create or replace function public.push_recipients(p_group uuid, p_except uuid)
returns table (user_id uuid, token text, muted_until timestamptz, unread bigint, unread_total bigint, unread_chats bigint)
language sql stable security definer set search_path = public as $$
  select m.user_id, t.token, m.muted_until, counts.here, totals.total, totals.chats
  from group_members m
  join device_tokens t on t.user_id = m.user_id
  left join lateral (
    select count(*) as here from messages u
    where u.group_id = p_group
      and u.id > coalesce((select rs.read_id from read_states rs where rs.group_id = p_group and rs.user_id = m.user_id), 0)
      and u.user_id is distinct from m.user_id
  ) counts on true
  left join lateral (
    select coalesce(sum(n), 0)::bigint as total, count(*) filter (where n > 0) as chats from (
      select (select count(*) from messages u
              where u.group_id = mine.group_id and u.id > coalesce(rs.read_id, 0)
                and u.user_id is distinct from m.user_id) as n
      from group_members mine
      left join read_states rs on rs.group_id = mine.group_id and rs.user_id = m.user_id
      where mine.user_id = m.user_id
    ) per_chat
  ) totals on true
  where m.group_id = p_group and m.user_id is distinct from p_except;
$$;
revoke execute on function public.push_recipients(uuid, uuid) from public, anon, authenticated;
grant execute on function public.push_recipients(uuid, uuid) to service_role;

create index if not exists messages_group_id_desc on public.messages (group_id, id desc);

------------------------------------------------------------------------------
-- More things worth a push (notify_push() is from 0006)
------------------------------------------------------------------------------

drop trigger if exists push_members on public.group_members;
create trigger push_members after insert on public.group_members
  for each row execute function public.notify_push();

drop trigger if exists push_user_keys on public.user_keys;
create trigger push_user_keys after insert or update of key_id on public.user_keys
  for each row execute function public.notify_push();

drop trigger if exists push_reactions on public.reactions;
create trigger push_reactions after insert or update of ciphertext on public.reactions
  for each row when (new.ciphertext is not null) execute function public.notify_push();
