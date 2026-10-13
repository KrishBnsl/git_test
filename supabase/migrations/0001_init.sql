-- gng: schema, row-level security, RPCs and realtime setup.
-- Run once in the Supabase SQL editor (or `supabase db push`).

------------------------------------------------------------------------------
-- Tables
------------------------------------------------------------------------------

create table public.profiles (
  id           uuid primary key references auth.users (id) on delete cascade,
  display_name text not null check (char_length(display_name) between 1 and 40),
  emoji        text not null default '😎',
  branch       text check (char_length(branch) <= 40),
  year         smallint check (year between 1 and 5),
  birthday     date,
  status_text  text check (char_length(status_text) <= 80),
  created_at   timestamptz not null default now()
);

create table public.groups (
  id          uuid primary key default gen_random_uuid(),
  name        text not null check (char_length(name) between 1 and 40),
  emoji       text not null default '🫶',
  invite_code text not null unique,
  created_by  uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  created_at  timestamptz not null default now()
);

create table public.group_members (
  group_id  uuid not null references public.groups (id) on delete cascade,
  user_id   uuid not null references public.profiles (id) on delete cascade,
  role      text not null default 'member' check (role in ('admin', 'member')),
  joined_at timestamptz not null default now(),
  primary key (group_id, user_id)
);
create index on public.group_members (user_id);

-- Chat. Non-text kinds are posted by triggers when plans, exams or materials are created.
create table public.messages (
  id         bigint generated always as identity primary key,
  group_id   uuid not null references public.groups (id) on delete cascade,
  user_id    uuid default auth.uid() references public.profiles (id) on delete set null,
  body       text not null check (char_length(body) between 1 and 2000),
  kind       text not null default 'text'
             check (kind in ('text', 'system', 'plan', 'exam', 'material')),
  ref_id     uuid,
  created_at timestamptz not null default now()
);
create index on public.messages (group_id, created_at desc);

create table public.reactions (
  message_id bigint not null references public.messages (id) on delete cascade,
  user_id    uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  group_id   uuid not null references public.groups (id) on delete cascade,
  emoji      text not null check (char_length(emoji) <= 16),
  primary key (message_id, user_id, emoji)
);

-- Lunch requests, evening snacks, outings, study sessions: all plans with RSVPs.
create table public.plans (
  id         uuid primary key default gen_random_uuid(),
  group_id   uuid not null references public.groups (id) on delete cascade,
  created_by uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  kind       text not null check (kind in ('lunch', 'snacks', 'outing', 'study', 'other')),
  title      text not null check (char_length(title) between 1 and 80),
  place      text check (char_length(place) <= 80),
  starts_at  timestamptz not null,
  note       text check (char_length(note) <= 280),
  created_at timestamptz not null default now()
);
create index on public.plans (group_id, starts_at);

create table public.plan_rsvps (
  plan_id    uuid not null references public.plans (id) on delete cascade,
  user_id    uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  group_id   uuid not null references public.groups (id) on delete cascade,
  answer     text not null check (answer in ('in', 'maybe', 'out')),
  updated_at timestamptz not null default now(),
  primary key (plan_id, user_id)
);

-- "What did you eat today?" board. One row per person per day, shared with all their groups.
create table public.lunch_logs (
  user_id    uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  day        date not null default (now() at time zone 'Asia/Kolkata')::date,
  dish       text not null check (char_length(dish) between 1 and 60),
  place      text check (char_length(place) <= 60),
  emoji      text not null default '🍛',
  created_at timestamptz not null default now(),
  primary key (user_id, day)
);

create table public.timetable_slots (
  id       uuid primary key default gen_random_uuid(),
  user_id  uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  day      smallint not null check (day between 1 and 7), -- ISO: 1 = Monday
  starts   time not null,
  ends     time not null,
  subject  text not null check (char_length(subject) between 1 and 40),
  room     text check (char_length(room) <= 20),
  check (ends > starts)
);
create index on public.timetable_slots (user_id);

-- Named spots on campus. group_id null = available to everyone.
create table public.zones (
  id         uuid primary key default gen_random_uuid(),
  group_id   uuid references public.groups (id) on delete cascade,
  created_by uuid default auth.uid() references public.profiles (id) on delete set null,
  name       text not null check (char_length(name) between 1 and 30),
  emoji      text not null default '📍',
  lat        double precision not null,
  lng        double precision not null,
  radius_m   int not null default 60 check (radius_m between 10 and 2000)
);

-- One row per user. Raw coordinates are only stored while live sharing,
-- and only exposed to group-mates through group_presence() while live_until is in the future.
create table public.locations (
  user_id    uuid primary key default auth.uid() references public.profiles (id) on delete cascade,
  mode       text not null default 'off' check (mode in ('off', 'zone', 'live')),
  zone_id    uuid references public.zones (id) on delete set null,
  lat        double precision,
  lng        double precision,
  live_until timestamptz,
  updated_at timestamptz not null default now()
);

create table public.exams (
  id         uuid primary key default gen_random_uuid(),
  group_id   uuid not null references public.groups (id) on delete cascade,
  created_by uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  subject    text not null check (char_length(subject) between 1 and 60),
  kind       text not null check (kind in ('midterm', 'endterm', 'quiz', 'lab', 'other')),
  exam_date  date not null,
  created_at timestamptz not null default now()
);

-- The material stack: notes, PYQs, slides. Either a link or a file in the `materials` bucket.
create table public.materials (
  id           uuid primary key default gen_random_uuid(),
  group_id     uuid not null references public.groups (id) on delete cascade,
  exam_id      uuid references public.exams (id) on delete set null,
  uploaded_by  uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  subject      text not null check (char_length(subject) between 1 and 60),
  title        text not null check (char_length(title) between 1 and 100),
  link_url     text,
  storage_path text,
  created_at   timestamptz not null default now(),
  check (link_url is not null or storage_path is not null)
);

create table public.material_votes (
  material_id uuid not null references public.materials (id) on delete cascade,
  user_id     uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  group_id    uuid not null references public.groups (id) on delete cascade,
  primary key (material_id, user_id)
);

------------------------------------------------------------------------------
-- Membership helpers (security definer so policies don't recurse into group_members RLS)
------------------------------------------------------------------------------

create function public.is_member(gid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from group_members where group_id = gid and user_id = auth.uid());
$$;

create function public.shares_group(other uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from group_members a
    join group_members b on a.group_id = b.group_id
    where a.user_id = auth.uid() and b.user_id = other
  );
$$;

-- Storage paths are text ("<group_id>/file.pdf"); compare as text so bad paths just fail the check.
create function public.is_member_folder(folder text) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from group_members where group_id::text = folder and user_id = auth.uid());
$$;

------------------------------------------------------------------------------
-- Row-level security
------------------------------------------------------------------------------

alter table public.profiles        enable row level security;
alter table public.groups          enable row level security;
alter table public.group_members   enable row level security;
alter table public.messages        enable row level security;
alter table public.reactions       enable row level security;
alter table public.plans           enable row level security;
alter table public.plan_rsvps      enable row level security;
alter table public.lunch_logs      enable row level security;
alter table public.timetable_slots enable row level security;
alter table public.zones           enable row level security;
alter table public.locations       enable row level security;
alter table public.exams           enable row level security;
alter table public.materials       enable row level security;
alter table public.material_votes  enable row level security;

-- profiles: you and your group-mates
create policy "read self and group-mates" on public.profiles for select
  using (id = auth.uid() or public.shares_group(id));
create policy "create own profile" on public.profiles for insert with check (id = auth.uid());
create policy "edit own profile" on public.profiles for update using (id = auth.uid());

-- groups: created and joined only through RPCs
create policy "members read group" on public.groups for select using (public.is_member(id));
create policy "admins edit group" on public.groups for update using (
  exists (select 1 from public.group_members m
          where m.group_id = groups.id and m.user_id = auth.uid() and m.role = 'admin'));

create policy "members read members" on public.group_members for select using (public.is_member(group_id));
create policy "leave group" on public.group_members for delete using (user_id = auth.uid());

-- messages
create policy "members read messages" on public.messages for select using (public.is_member(group_id));
create policy "members send text" on public.messages for insert
  with check (public.is_member(group_id) and user_id = auth.uid() and kind = 'text');
create policy "delete own messages" on public.messages for delete using (user_id = auth.uid());

create policy "members read reactions" on public.reactions for select using (public.is_member(group_id));
create policy "react" on public.reactions for insert with check (
  user_id = auth.uid() and public.is_member(group_id)
  and exists (select 1 from public.messages m where m.id = message_id and m.group_id = reactions.group_id));
create policy "unreact" on public.reactions for delete using (user_id = auth.uid());

-- plans
create policy "members read plans" on public.plans for select using (public.is_member(group_id));
create policy "members create plans" on public.plans for insert
  with check (public.is_member(group_id) and created_by = auth.uid());
create policy "creator edits plan" on public.plans for update using (created_by = auth.uid());
create policy "creator deletes plan" on public.plans for delete using (created_by = auth.uid());

create policy "members read rsvps" on public.plan_rsvps for select using (public.is_member(group_id));
create policy "rsvp" on public.plan_rsvps for insert with check (
  user_id = auth.uid() and public.is_member(group_id)
  and exists (select 1 from public.plans p where p.id = plan_id and p.group_id = plan_rsvps.group_id));
create policy "change rsvp" on public.plan_rsvps for update using (user_id = auth.uid());

-- lunch board
create policy "read own and group-mates lunch" on public.lunch_logs for select
  using (user_id = auth.uid() or public.shares_group(user_id));
create policy "log lunch" on public.lunch_logs for insert with check (user_id = auth.uid());
create policy "edit lunch" on public.lunch_logs for update using (user_id = auth.uid());

-- timetable
create policy "read own and group-mates classes" on public.timetable_slots for select
  using (user_id = auth.uid() or public.shares_group(user_id));
create policy "add class" on public.timetable_slots for insert with check (user_id = auth.uid());
create policy "edit class" on public.timetable_slots for update using (user_id = auth.uid());
create policy "remove class" on public.timetable_slots for delete using (user_id = auth.uid());

-- zones
create policy "read zones" on public.zones for select using (group_id is null or public.is_member(group_id));
create policy "pin zone" on public.zones for insert
  with check (group_id is not null and public.is_member(group_id) and created_by = auth.uid());
create policy "unpin own zone" on public.zones for delete using (created_by = auth.uid());

-- locations: only your own row directly; others go through group_presence()
create policy "own location" on public.locations for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- study
create policy "members read exams" on public.exams for select using (public.is_member(group_id));
create policy "members add exams" on public.exams for insert
  with check (public.is_member(group_id) and created_by = auth.uid());
create policy "creator removes exam" on public.exams for delete using (created_by = auth.uid());

create policy "members read materials" on public.materials for select using (public.is_member(group_id));
create policy "members stack materials" on public.materials for insert
  with check (public.is_member(group_id) and uploaded_by = auth.uid());
create policy "uploader removes material" on public.materials for delete using (uploaded_by = auth.uid());

create policy "members read votes" on public.material_votes for select using (public.is_member(group_id));
create policy "vote" on public.material_votes for insert with check (
  user_id = auth.uid() and public.is_member(group_id)
  and exists (select 1 from public.materials m where m.id = material_id and m.group_id = material_votes.group_id));
create policy "unvote" on public.material_votes for delete using (user_id = auth.uid());

------------------------------------------------------------------------------
-- RPCs
------------------------------------------------------------------------------

create function public.create_group(p_name text, p_emoji text default '🫶')
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
  values (p_name, coalesce(nullif(p_emoji, ''), '🫶'), code, auth.uid())
  returning * into g;
  insert into group_members (group_id, user_id, role) values (g.id, auth.uid(), 'admin');
  insert into messages (group_id, user_id, body, kind)
  values (g.id, null, g.emoji || ' ' || g.name || ' is live! Share code ' || code || ' with the gang.', 'system');
  return g;
end;
$$;

create function public.join_group(p_code text)
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
    values (g.id, null, coalesce(who, 'Someone') || ' just pulled up 🎉', 'system');
  end if;
  return g;
end;
$$;

-- Where is everyone? Coordinates only while that person is live sharing.
create function public.group_presence(gid uuid)
returns table (
  user_id uuid, mode text, zone_name text, zone_emoji text,
  lat double precision, lng double precision, live_until timestamptz, updated_at timestamptz
)
language sql stable security definer set search_path = public as $$
  select l.user_id,
         case when l.mode = 'live' and l.live_until <= now() then 'zone' else l.mode end,
         z.name, z.emoji,
         case when l.mode = 'live' and l.live_until > now() then l.lat end,
         case when l.mode = 'live' and l.live_until > now() then l.lng end,
         case when l.mode = 'live' and l.live_until > now() then l.live_until end,
         l.updated_at
  from locations l
  join group_members m on m.user_id = l.user_id and m.group_id = gid
  left join zones z on z.id = l.zone_id
  where public.is_member(gid) and l.mode <> 'off';
$$;

------------------------------------------------------------------------------
-- Triggers: drop activity into the chat so it feels alive
------------------------------------------------------------------------------

create function public.announce() returns trigger
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
      when 'lunch'  then '🍛 Lunch? ' || new.title
      when 'snacks' then '🍟 Snacks run: ' || new.title
      when 'outing' then '🚗 Outing: ' || new.title
      when 'study'  then '📚 Study sesh: ' || new.title
      else '✨ ' || new.title end
      || coalesce(' @ ' || new.place, '') || ' · ' || to_char(ist, 'Dy FMHH12:MI am');
  elsif tg_table_name = 'exams' then
    kind := 'exam';
    body := '⏰ ' || initcap(new.kind) || ': ' || new.subject || ' on ' || to_char(new.exam_date, 'Dy DD Mon');
  else
    kind := 'material';
    body := '📎 New in the stack: ' || new.title || ' (' || new.subject || ')';
  end if;
  insert into messages (group_id, user_id, body, kind, ref_id)
  values (new.group_id, auth.uid(), body, kind, new.id);
  return new;
end;
$$;

create trigger plans_announce after insert on public.plans for each row execute function public.announce();
create trigger exams_announce after insert on public.exams for each row execute function public.announce();
create trigger materials_announce after insert on public.materials for each row execute function public.announce();

-- Whoever makes a plan is automatically in.
create function public.auto_rsvp() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into plan_rsvps (plan_id, user_id, group_id, answer) values (new.id, new.created_by, new.group_id, 'in');
  return new;
end;
$$;
create trigger plans_auto_rsvp after insert on public.plans for each row execute function public.auto_rsvp();

------------------------------------------------------------------------------
-- Storage: private bucket, files live under <group_id>/...
------------------------------------------------------------------------------

insert into storage.buckets (id, name, public) values ('materials', 'materials', false)
on conflict (id) do nothing;

create policy "members read group files" on storage.objects for select
  using (bucket_id = 'materials' and public.is_member_folder((storage.foldername(name))[1]));
create policy "members upload group files" on storage.objects for insert
  with check (bucket_id = 'materials' and public.is_member_folder((storage.foldername(name))[1]));
create policy "owners delete files" on storage.objects for delete
  using (bucket_id = 'materials' and owner = auth.uid());

------------------------------------------------------------------------------
-- Realtime
------------------------------------------------------------------------------

alter publication supabase_realtime add table
  public.messages, public.reactions, public.plans, public.plan_rsvps, public.materials;

------------------------------------------------------------------------------
-- Seed: one shared zone for the whole campus. Groups pin finer spots
-- (Library, Mic Mac, hostels, OAT...) from the app while standing there.
------------------------------------------------------------------------------

insert into public.zones (group_id, created_by, name, emoji, lat, lng, radius_m)
values (null, null, 'DTU campus', '🏫', 28.7499, 77.1170, 700);
