-- gng 0002: profile photos, icon keys instead of emoji, plain-text chat events.
-- Run once in the Supabase SQL editor, after 0001_init.sql.

------------------------------------------------------------------------------
-- Profile photos
------------------------------------------------------------------------------

alter table public.profiles add column if not exists avatar_url text;

-- Public bucket: photos are served from unguessable URLs that only group-mates ever receive
-- (profile rows are protected by row-level security). Each person can only write to their own folder.
insert into storage.buckets (id, name, public) values ('avatars', 'avatars', true)
on conflict (id) do nothing;

-- Reading is open through the public URL; this select policy only lets the uploader read back their own rows.
create policy "read own avatar rows" on storage.objects for select
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "upload own avatar" on storage.objects for insert
  with check (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "replace own avatar" on storage.objects for update
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "delete own avatar" on storage.objects for delete
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);

------------------------------------------------------------------------------
-- The `emoji` columns on groups, zones and lunch_logs now hold icon keys
-- (see app/.../ui/icons/IconChoices.kt). Convert anything that is still an emoji.
------------------------------------------------------------------------------

alter table public.groups     alter column emoji set default 'groups';
alter table public.zones      alter column emoji set default 'location_on';
alter table public.lunch_logs alter column emoji set default 'lunch_dining';

update public.groups     set emoji = 'groups'       where emoji !~ '^[a-z_]+$';
update public.zones      set emoji = case when group_id is null then 'school' else 'location_on' end
                         where emoji !~ '^[a-z_]+$';
update public.lunch_logs set emoji = 'lunch_dining' where emoji !~ '^[a-z_]+$';

------------------------------------------------------------------------------
-- Chat events as plain text: the app draws the icon for each kind itself.
------------------------------------------------------------------------------

create or replace function public.create_group(p_name text, p_emoji text default 'groups')
returns public.groups
language plpgsql security definer set search_path = public as $$
declare
  g public.groups;
  code text;
begin
  if auth.uid() is null then raise exception 'not signed in'; end if;
  loop
    code := upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 6));
    exit when not exists (select 1 from groups where invite_code = code);
  end loop;
  insert into groups (name, emoji, invite_code, created_by)
  values (p_name, coalesce(nullif(p_emoji, ''), 'groups'), code, auth.uid())
  returning * into g;
  insert into group_members (group_id, user_id, role) values (g.id, auth.uid(), 'admin');
  insert into messages (group_id, user_id, body, kind)
  values (g.id, null, g.name || ' is live. Share the code ' || code || ' to bring in the gang.', 'system');
  return g;
end;
$$;

create or replace function public.join_group(p_code text)
returns public.groups
language plpgsql security definer set search_path = public as $$
declare
  g public.groups;
  who text;
begin
  if auth.uid() is null then raise exception 'not signed in'; end if;
  select * into g from groups where invite_code = upper(trim(p_code));
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

create or replace function public.announce() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  body text;
  kind text;
  ist timestamp;
begin
  if tg_table_name = 'plans' then
    ist := new.starts_at at time zone 'Asia/Kolkata';
    kind := 'plan';
    body := case new.kind
      when 'lunch'  then 'Lunch: '
      when 'snacks' then 'Snacks: '
      when 'outing' then 'Outing: '
      when 'study'  then 'Study session: '
      else '' end
      || new.title || coalesce(' at ' || new.place, '') || ' · ' || to_char(ist, 'Dy FMHH12:MI am');
  elsif tg_table_name = 'exams' then
    kind := 'exam';
    body := initcap(new.kind) || ': ' || new.subject || ' on ' || to_char(new.exam_date, 'Dy DD Mon');
  else
    kind := 'material';
    body := new.title || ' added to ' || new.subject;
  end if;
  insert into messages (group_id, user_id, body, kind, ref_id)
  values (new.group_id, auth.uid(), body, kind, new.id);
  return new;
end;
$$;
