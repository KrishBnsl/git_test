-- gng 0007: @mentions in chats.
-- Run after 0006. Safe to run more than once.
--
-- The message itself stays end-to-end encrypted. Who was tagged is stored next to it in plain
-- form, so the server can notify a tagged person even if they muted the chat, and show an @
-- badge on the home list. Only current members of the conversation can be tagged.

alter table public.messages add column if not exists mentions uuid[];

drop policy if exists "members send encrypted text" on public.messages;
create policy "members send encrypted text" on public.messages for insert
  with check (
    public.can_send_messages(group_id) and user_id = auth.uid() and kind = 'text'
    and body is null and ciphertext is not null and epoch is not null
    and (mentions is null or (
      cardinality(mentions) <= 50
      and not exists (
        select 1 from unnest(mentions) as tagged(id)
        where not exists (select 1 from public.group_members g where g.group_id = messages.group_id and g.user_id = tagged.id)))));

-- The home list, now with how many unread messages tag you.
drop function if exists public.my_conversations();
create function public.my_conversations()
returns table (
  id uuid, name text, emoji text, photo_url text, kind text, invite_code text, created_by uuid,
  other_user_id uuid, muted_until timestamptz,
  last_message_id bigint, last_message_at timestamptz, last_message_kind text,
  last_message_user_id uuid, last_message_body text, unread_count bigint, unread_mentions bigint
)
language sql stable security definer set search_path = public as $$
  select g.id, g.name, g.emoji, g.photo_url, g.kind, g.invite_code, g.created_by,
         (select o.user_id from group_members o where o.group_id = g.id and o.user_id <> auth.uid() limit 1),
         m.muted_until,
         last.id, coalesce(last.created_at, g.created_at), last.kind, last.user_id, last.body,
         (select count(*) from messages u
            where u.group_id = g.id and u.id > coalesce(rs.read_id, 0)
              and u.user_id is distinct from auth.uid()),
         (select count(*) from messages u
            where u.group_id = g.id and u.id > coalesce(rs.read_id, 0)
              and u.user_id is distinct from auth.uid() and auth.uid() = any (u.mentions))
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
