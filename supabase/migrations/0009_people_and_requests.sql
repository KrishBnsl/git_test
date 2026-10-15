-- gng 0009: usernames, colleges, finding people, and DM requests.
-- Run after 0008. Safe to run more than once.
--
-- Everyone gets an @username (made from their name; they can change it). You can search anyone on
-- gng by username or name and message them. If you don't share a gang, it arrives as a message
-- request, like Instagram: they see it under "Message requests" and accept or decline.
-- Requests are encrypted like any other chat.

------------------------------------------------------------------------------
-- Usernames and colleges
------------------------------------------------------------------------------

do $$
begin
  -- Everyone on gng so far is from DTU, so existing profiles start there (only the first time).
  if not exists (select 1 from information_schema.columns
                 where table_schema = 'public' and table_name = 'profiles' and column_name = 'college') then
    alter table public.profiles add column college text;
    update public.profiles set college = 'Delhi Technological University (DTU)';
  end if;
end $$;

alter table public.profiles add column if not exists username text;
alter table public.profiles drop constraint if exists profiles_username_format;
alter table public.profiles add constraint profiles_username_format check (username ~ '^[a-z0-9._]{3,20}$');
alter table public.profiles drop constraint if exists profiles_college_length;
alter table public.profiles add constraint profiles_college_length check (char_length(college) <= 80);
create unique index if not exists profiles_username_unique on public.profiles (username);

-- "Aarav Mehta" -> "aaravmehta", then aaravmehta2, aaravmehta3, ... until it's free.
create or replace function public.free_username(p_name text, p_self uuid) returns text
language plpgsql stable security definer set search_path = public as $$
declare
  base text := left(regexp_replace(lower(coalesce(p_name, '')), '[^a-z0-9._]', '', 'g'), 16);
  candidate text;
  n int := 1;
begin
  if char_length(base) < 3 then base := rpad(base, 3, 'x'); end if;
  candidate := base;
  while exists (select 1 from profiles where username = candidate and id <> p_self) loop
    n := n + 1;
    candidate := base || n;
  end loop;
  return candidate;
end;
$$;

create or replace function public.default_username() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if new.username is null then new.username := public.free_username(new.display_name, new.id); end if;
  new.username := lower(new.username);
  return new;
end;
$$;
drop trigger if exists profiles_default_username on public.profiles;
create trigger profiles_default_username before insert or update of username on public.profiles
  for each row execute function public.default_username();

do $$
declare
  p record;
begin
  for p in select id, display_name from public.profiles where username is null order by created_at loop
    update public.profiles set username = public.free_username(p.display_name, p.id) where id = p.id;
  end loop;
end $$;

-- Whether a username is free (for the profile form).
create or replace function public.username_available(p_username text) returns boolean
language sql stable security definer set search_path = public as $$
  select lower(p_username) ~ '^[a-z0-9._]{3,20}$'
     and not exists (select 1 from profiles where username = lower(p_username) and id <> auth.uid());
$$;

------------------------------------------------------------------------------
-- Finding people: anyone signed in can look others up by username or name.
-- Only the public card is returned (name, username, photo, college); the rest of a profile stays
-- visible to gang-mates only.
------------------------------------------------------------------------------

drop function if exists public.search_people(text);
create function public.search_people(q text)
returns table (id uuid, display_name text, username text, avatar_url text, college text, shares_gang boolean)
language sql stable security definer set search_path = public as $$
  with needle as (select lower(trim(both from ltrim(trim(q), '@'))) as n)
  select p.id, p.display_name, p.username, p.avatar_url, p.college,
         exists (select 1 from group_members a join group_members b on a.group_id = b.group_id
                 join groups g on g.id = a.group_id and g.kind = 'gang'
                 where a.user_id = auth.uid() and b.user_id = p.id)
  from profiles p, needle
  where auth.uid() is not null and p.id <> auth.uid() and char_length(needle.n) >= 2
    and (p.username like needle.n || '%' or lower(p.display_name) like '%' || needle.n || '%')
  order by (p.username = needle.n) desc, (p.username like needle.n || '%') desc, p.display_name
  limit 25;
$$;

------------------------------------------------------------------------------
-- DM requests: a DM between people who don't share a gang starts as 'pending'.
------------------------------------------------------------------------------

alter table public.groups
  add column if not exists dm_status    text not null default 'accepted',
  add column if not exists requested_by uuid references public.profiles (id) on delete cascade;
alter table public.groups drop constraint if exists groups_dm_status_check;
alter table public.groups add constraint groups_dm_status_check check (dm_status in ('pending', 'accepted'));

-- Opens the DM with anyone on gng. Gang-mates get a normal DM; anyone else gets a request.
-- If they had already sent you a request, this accepts it.
create or replace function public.request_direct_chat(p_other uuid) returns public.groups
language plpgsql security definer set search_path = public as $$
declare
  me uuid := auth.uid();
  k text;
  g public.groups;
  gang_mates boolean;
begin
  if me is null then raise exception 'not signed in'; end if;
  if p_other = me then raise exception 'you can''t message yourself'; end if;
  if not exists (select 1 from profiles where id = p_other) then raise exception 'no such person'; end if;
  gang_mates := exists (select 1 from group_members a join group_members b on a.group_id = b.group_id
                        join groups x on x.id = a.group_id and x.kind = 'gang'
                        where a.user_id = me and b.user_id = p_other);
  k := least(me::text, p_other::text) || ':' || greatest(me::text, p_other::text);
  select * into g from groups where dm_key = k;
  if g.id is null then
    insert into groups (name, emoji, invite_code, created_by, kind, dm_key, dm_status, requested_by)
    values ('Direct message', 'chat_bubble', 'DM' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 10)), me, 'dm', k,
            case when gang_mates then 'accepted' else 'pending' end, case when gang_mates then null else me end)
    returning * into g;
  elsif g.dm_status = 'pending' and (gang_mates or g.requested_by = p_other) then
    update groups set dm_status = 'accepted' where id = g.id returning * into g;
  end if;
  insert into group_members (group_id, user_id) values (g.id, me), (g.id, p_other) on conflict do nothing;
  return g;
end;
$$;

-- The person who got a request accepts it (it becomes a normal DM) or declines it (it's deleted).
create or replace function public.respond_dm_request(gid uuid, p_accept boolean) returns void
language plpgsql security definer set search_path = public as $$
declare
  g public.groups;
begin
  select * into g from groups where id = gid and kind = 'dm' and dm_status = 'pending';
  if g.id is null then return; end if;
  if not public.is_member(gid) or g.requested_by = auth.uid() then raise exception 'not your request'; end if;
  if p_accept then
    update groups set dm_status = 'accepted' where id = gid;
  else
    delete from groups where id = gid;
  end if;
end;
$$;

-- Only the person who sent a request can write in it until it's accepted.
drop policy if exists "members send encrypted text" on public.messages;
create policy "members send encrypted text" on public.messages for insert
  with check (
    public.can_send_messages(group_id) and user_id = auth.uid() and kind = 'text'
    and body is null and ciphertext is not null and epoch is not null
    and not exists (select 1 from public.groups g where g.id = group_id and g.dm_status = 'pending' and g.requested_by <> auth.uid())
    and (mentions is null or (
      cardinality(mentions) <= 50
      and not exists (
        select 1 from unnest(mentions) as tagged(id)
        where not exists (select 1 from public.group_members m where m.group_id = messages.group_id and m.user_id = tagged.id)))));

-- The home list, now saying which DMs are requests.
drop function if exists public.my_conversations();
create function public.my_conversations()
returns table (
  id uuid, name text, emoji text, photo_url text, kind text, invite_code text, created_by uuid,
  other_user_id uuid, muted_until timestamptz,
  last_message_id bigint, last_message_at timestamptz, last_message_kind text,
  last_message_user_id uuid, last_message_body text, unread_count bigint, unread_mentions bigint,
  dm_status text, requested_by uuid
)
language sql stable security definer set search_path = public as $$
  select g.id, g.name, g.emoji, g.photo_url, g.kind, g.invite_code, g.created_by,
         (select o.user_id from group_members o where o.group_id = g.id and o.user_id <> auth.uid() limit 1),
         m.muted_until,
         last.id, coalesce(last.created_at, g.created_at), last.kind, last.user_id, last.body,
         (select count(*) from messages u
            where u.group_id = g.id and u.id > greatest(coalesce(rs.read_id, 0), m.history_from - 1)
              and u.user_id is distinct from auth.uid()),
         (select count(*) from messages u
            where u.group_id = g.id and u.id > greatest(coalesce(rs.read_id, 0), m.history_from - 1)
              and u.user_id is distinct from auth.uid() and auth.uid() = any (u.mentions)),
         g.dm_status, g.requested_by
  from group_members m
  join groups g on g.id = m.group_id
  left join read_states rs on rs.group_id = g.id and rs.user_id = auth.uid()
  left join lateral (
    select x.id, x.created_at, x.kind, x.user_id, x.body from messages x
    where x.group_id = g.id and x.id >= m.history_from order by x.id desc limit 1
  ) last on true
  where m.user_id = auth.uid()
  order by coalesce(last.created_at, g.created_at) desc;
$$;

------------------------------------------------------------------------------
-- Profiles: the full profile (birthday, branch, year, status) is for gang-mates and accepted DMs.
-- In a pending request each side only sees the other's public card.
------------------------------------------------------------------------------

create or replace function public.shares_group_accepted(other uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from group_members a
    join group_members b on a.group_id = b.group_id
    join groups g on g.id = a.group_id and not (g.kind = 'dm' and g.dm_status = 'pending')
    where a.user_id = auth.uid() and b.user_id = other);
$$;

drop policy if exists "read self and group-mates" on public.profiles;
create policy "read self and group-mates" on public.profiles for select
  using (id = auth.uid() or public.shares_group_accepted(id));

-- Members of a conversation with their profiles (just the card where that's all you may see).
create or replace function public.group_member_profiles(gid uuid)
returns table (user_id uuid, role text, profiles jsonb)
language sql stable security definer set search_path = public as $$
  select m.user_id, m.role,
         case when m.user_id = auth.uid() or public.shares_group_accepted(m.user_id) then to_jsonb(p)
              else jsonb_build_object('id', p.id, 'display_name', p.display_name, 'avatar_url', p.avatar_url,
                                      'username', p.username, 'college', p.college) end
  from group_members m join profiles p on p.id = m.user_id
  where m.group_id = gid and public.is_member(gid);
$$;
