-- gng 0003: group photo and settings, admin-controlled permissions, and map-ready presence.
-- Run in the Supabase SQL editor, after 0001 and 0002. Safe to run more than once.

------------------------------------------------------------------------------
-- Group photo and permissions
------------------------------------------------------------------------------

alter table public.groups
  add column if not exists photo_url text,
  add column if not exists edit_permission text not null default 'members'
    check (edit_permission in ('admins', 'members')),
  add column if not exists message_permission text not null default 'everyone'
    check (message_permission in ('everyone', 'admins'));

comment on column public.groups.edit_permission is 'Who may change the group photo, name and icon: admins or members';
comment on column public.groups.message_permission is 'Who may send chat messages: everyone or admins';

------------------------------------------------------------------------------
-- Permission helpers (security definer so policies can use them without recursing into RLS)
------------------------------------------------------------------------------

create or replace function public.is_group_admin(gid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from group_members where group_id = gid and user_id = auth.uid() and role = 'admin');
$$;

create or replace function public.can_edit_group(gid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from groups g join group_members m on m.group_id = g.id
    where g.id = gid and m.user_id = auth.uid()
      and (m.role = 'admin' or g.edit_permission = 'members'));
$$;

create or replace function public.can_send_messages(gid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from groups g join group_members m on m.group_id = g.id
    where g.id = gid and m.user_id = auth.uid()
      and (m.role = 'admin' or g.message_permission = 'everyone'));
$$;

-- Same check for storage paths ("<group_id>/file.jpg"), where the folder arrives as text.
create or replace function public.can_edit_group_folder(folder text) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from groups g join group_members m on m.group_id = g.id
    where g.id::text = folder and m.user_id = auth.uid()
      and (m.role = 'admin' or g.edit_permission = 'members'));
$$;

------------------------------------------------------------------------------
-- Enforcement
------------------------------------------------------------------------------

-- Group rows are no longer updated directly: every change goes through the functions below,
-- so members can change the photo but never the permissions.
drop policy if exists "admins edit group" on public.groups;

-- Chat: only people allowed to send messages can post.
drop policy if exists "members send text" on public.messages;
create policy "members send text" on public.messages for insert
  with check (
    public.can_send_messages(group_id) and user_id = auth.uid() and kind = 'text');

------------------------------------------------------------------------------
-- RPCs
------------------------------------------------------------------------------

create or replace function public.update_group_info(
  p_group uuid,
  p_name text default null,
  p_icon text default null,
  p_photo_url text default null,
  p_clear_photo boolean default false
) returns public.groups
language plpgsql security definer set search_path = public as $$
declare
  g public.groups;
begin
  if not public.can_edit_group(p_group) then
    raise exception 'you are not allowed to edit this gang';
  end if;
  -- Photos must come from this group's own folder in the group-photos bucket.
  if p_photo_url is not null and p_photo_url not like '%/storage/v1/object/public/group-photos/' || p_group || '/%' then
    raise exception 'invalid photo';
  end if;
  update groups set
    name = coalesce(nullif(trim(p_name), ''), name),
    emoji = coalesce(nullif(trim(p_icon), ''), emoji),
    photo_url = case when p_clear_photo then null else coalesce(p_photo_url, photo_url) end
  where id = p_group
  returning * into g;
  return g;
end;
$$;

create or replace function public.update_group_permissions(
  p_group uuid,
  p_edit text default null,
  p_message text default null
) returns public.groups
language plpgsql security definer set search_path = public as $$
declare
  g public.groups;
begin
  if not public.is_group_admin(p_group) then
    raise exception 'only admins can change permissions';
  end if;
  update groups set
    edit_permission = coalesce(p_edit, edit_permission),
    message_permission = coalesce(p_message, message_permission)
  where id = p_group
  returning * into g;
  return g;
end;
$$;

create or replace function public.set_member_role(p_group uuid, p_user uuid, p_role text)
returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_group_admin(p_group) then
    raise exception 'only admins can change roles';
  end if;
  if p_role not in ('admin', 'member') then
    raise exception 'unknown role';
  end if;
  if not exists (select 1 from group_members where group_id = p_group and user_id = p_user) then
    raise exception 'that person is not in this gang';
  end if;
  if p_role = 'member' and not exists (
    select 1 from group_members where group_id = p_group and role = 'admin' and user_id <> p_user) then
    raise exception 'a gang needs at least one admin';
  end if;
  update group_members set role = p_role where group_id = p_group and user_id = p_user;
end;
$$;

-- If the last admin leaves, the longest-standing member becomes admin so the gang is never orphaned.
create or replace function public.ensure_admin_remains() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if old.role = 'admin'
     and not exists (select 1 from group_members where group_id = old.group_id and role = 'admin') then
    update group_members set role = 'admin'
    where group_id = old.group_id
      and user_id = (select user_id from group_members where group_id = old.group_id order by joined_at limit 1);
  end if;
  return null;
end;
$$;

drop trigger if exists group_members_keep_admin on public.group_members;
create trigger group_members_keep_admin after delete on public.group_members
  for each row execute function public.ensure_admin_remains();

------------------------------------------------------------------------------
-- Group photo storage: public bucket, files under <group_id>/
------------------------------------------------------------------------------

insert into storage.buckets (id, name, public) values ('group-photos', 'group-photos', true)
on conflict (id) do nothing;

drop policy if exists "read group photo rows" on storage.objects;
create policy "read group photo rows" on storage.objects for select
  using (bucket_id = 'group-photos' and public.can_edit_group_folder((storage.foldername(name))[1]));
drop policy if exists "upload group photo" on storage.objects;
create policy "upload group photo" on storage.objects for insert
  with check (bucket_id = 'group-photos' and public.can_edit_group_folder((storage.foldername(name))[1]));
drop policy if exists "replace group photo" on storage.objects;
create policy "replace group photo" on storage.objects for update
  using (bucket_id = 'group-photos' and public.can_edit_group_folder((storage.foldername(name))[1]));
drop policy if exists "delete group photo" on storage.objects;
create policy "delete group photo" on storage.objects for delete
  using (bucket_id = 'group-photos' and public.can_edit_group_folder((storage.foldername(name))[1]));

------------------------------------------------------------------------------
-- Realtime: so a changed photo or permission reaches everyone's open screen
------------------------------------------------------------------------------

do $$
begin
  if not exists (
    select 1 from pg_publication_tables
    where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'groups') then
    alter publication supabase_realtime add table public.groups;
  end if;
end $$;

------------------------------------------------------------------------------
-- Presence for the map: also return the pinned spot's coordinates, so someone in
-- "spot" mode can be drawn on their spot without ever revealing where exactly they are.
------------------------------------------------------------------------------

drop function if exists public.group_presence(uuid);
create function public.group_presence(gid uuid)
returns table (
  user_id uuid, mode text, zone_name text, zone_emoji text,
  zone_lat double precision, zone_lng double precision,
  lat double precision, lng double precision, live_until timestamptz, updated_at timestamptz
)
language sql stable security definer set search_path = public as $$
  select l.user_id,
         case when l.mode = 'live' and l.live_until <= now() then 'zone' else l.mode end,
         z.name, z.emoji, z.lat, z.lng,
         case when l.mode = 'live' and l.live_until > now() then l.lat end,
         case when l.mode = 'live' and l.live_until > now() then l.lng end,
         case when l.mode = 'live' and l.live_until > now() then l.live_until end,
         l.updated_at
  from locations l
  join group_members m on m.user_id = l.user_id and m.group_id = gid
  left join zones z on z.id = l.zone_id
  where public.is_member(gid) and l.mode <> 'off';
$$;
