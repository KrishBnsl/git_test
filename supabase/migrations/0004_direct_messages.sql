-- gng 0004: direct messages between people who share a gang.
-- Run in the Supabase SQL editor, after 0001 to 0003. Safe to run more than once.

------------------------------------------------------------------------------
-- Tables
------------------------------------------------------------------------------

-- One thread per pair of people. user_a < user_b keeps the pair canonical, so there can never be two.
create table if not exists public.dm_threads (
  id         uuid primary key default gen_random_uuid(),
  user_a     uuid not null references public.profiles (id) on delete cascade,
  user_b     uuid not null references public.profiles (id) on delete cascade,
  created_at timestamptz not null default now(),
  check (user_a < user_b),
  unique (user_a, user_b)
);
create index if not exists dm_threads_user_b on public.dm_threads (user_b);

create table if not exists public.dm_messages (
  id         bigint generated always as identity primary key,
  thread_id  uuid not null references public.dm_threads (id) on delete cascade,
  sender_id  uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  body       text not null check (char_length(body) between 1 and 2000),
  created_at timestamptz not null default now()
);
create index if not exists dm_messages_thread on public.dm_messages (thread_id, id desc);

create table if not exists public.dm_reactions (
  message_id bigint not null references public.dm_messages (id) on delete cascade,
  thread_id  uuid not null references public.dm_threads (id) on delete cascade,
  user_id    uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  emoji      text not null check (char_length(emoji) <= 16),
  primary key (message_id, user_id, emoji)
);

-- How far each person has read, for unread counts.
create table if not exists public.dm_reads (
  thread_id            uuid not null references public.dm_threads (id) on delete cascade,
  user_id              uuid not null references public.profiles (id) on delete cascade,
  last_read_message_id bigint not null default 0,
  primary key (thread_id, user_id)
);

------------------------------------------------------------------------------
-- Helpers
------------------------------------------------------------------------------

create or replace function public.is_dm_member(tid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from dm_threads where id = tid and auth.uid() in (user_a, user_b));
$$;

-- You can send only while you still share at least one gang with the other person.
create or replace function public.can_dm(tid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from dm_threads t
    where t.id = tid and auth.uid() in (t.user_a, t.user_b)
      and public.shares_group(case when t.user_a = auth.uid() then t.user_b else t.user_a end));
$$;

------------------------------------------------------------------------------
-- Row-level security
------------------------------------------------------------------------------

alter table public.dm_threads   enable row level security;
alter table public.dm_messages  enable row level security;
alter table public.dm_reactions enable row level security;
alter table public.dm_reads     enable row level security;

-- Threads are created only through open_dm(), never directly.
drop policy if exists "read own dm threads" on public.dm_threads;
create policy "read own dm threads" on public.dm_threads for select
  using (auth.uid() in (user_a, user_b));

drop policy if exists "read dm messages" on public.dm_messages;
create policy "read dm messages" on public.dm_messages for select
  using (public.is_dm_member(thread_id));
drop policy if exists "send dm messages" on public.dm_messages;
create policy "send dm messages" on public.dm_messages for insert
  with check (sender_id = auth.uid() and public.can_dm(thread_id));
drop policy if exists "delete own dm messages" on public.dm_messages;
create policy "delete own dm messages" on public.dm_messages for delete
  using (sender_id = auth.uid());

drop policy if exists "read dm reactions" on public.dm_reactions;
create policy "read dm reactions" on public.dm_reactions for select
  using (public.is_dm_member(thread_id));
drop policy if exists "dm react" on public.dm_reactions;
create policy "dm react" on public.dm_reactions for insert
  with check (
    user_id = auth.uid() and public.is_dm_member(thread_id)
    and exists (select 1 from public.dm_messages m where m.id = message_id and m.thread_id = dm_reactions.thread_id));
drop policy if exists "dm unreact" on public.dm_reactions;
create policy "dm unreact" on public.dm_reactions for delete
  using (user_id = auth.uid());

-- Read markers are written only through mark_dm_read().
drop policy if exists "read own dm reads" on public.dm_reads;
create policy "read own dm reads" on public.dm_reads for select
  using (user_id = auth.uid());

------------------------------------------------------------------------------
-- RPCs
------------------------------------------------------------------------------

-- Finds or creates the thread with another person, if you share a gang.
create or replace function public.open_dm(p_other uuid)
returns public.dm_threads
language plpgsql security definer set search_path = public as $$
declare
  t public.dm_threads;
  a uuid;
  b uuid;
begin
  if auth.uid() is null then raise exception 'not signed in'; end if;
  if p_other = auth.uid() then raise exception 'you can''t message yourself'; end if;
  if not public.shares_group(p_other) then raise exception 'you can only message people in your gangs'; end if;
  a := least(auth.uid(), p_other);
  b := greatest(auth.uid(), p_other);
  insert into dm_threads (user_a, user_b) values (a, b) on conflict (user_a, user_b) do nothing;
  select * into t from dm_threads where user_a = a and user_b = b;
  return t;
end;
$$;

create or replace function public.mark_dm_read(p_thread uuid)
returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_dm_member(p_thread) then raise exception 'not your conversation'; end if;
  insert into dm_reads (thread_id, user_id, last_read_message_id)
  values (p_thread, auth.uid(), coalesce((select max(id) from dm_messages where thread_id = p_thread), 0))
  on conflict (thread_id, user_id) do update
    set last_read_message_id = greatest(dm_reads.last_read_message_id, excluded.last_read_message_id);
end;
$$;

-- The inbox: every conversation with the last message and how many you haven't read.
create or replace function public.dm_inbox()
returns table (
  thread_id uuid, other_id uuid, last_body text, last_sender uuid, last_at timestamptz, unread integer
)
language sql stable security definer set search_path = public as $$
  select t.id,
         case when t.user_a = auth.uid() then t.user_b else t.user_a end,
         lm.body, lm.sender_id, coalesce(lm.created_at, t.created_at),
         (select count(*)::integer from dm_messages m
          where m.thread_id = t.id and m.sender_id <> auth.uid()
            and m.id > coalesce(r.last_read_message_id, 0))
  from dm_threads t
  left join lateral (
    select body, sender_id, created_at from dm_messages where thread_id = t.id order by id desc limit 1
  ) lm on true
  left join dm_reads r on r.thread_id = t.id and r.user_id = auth.uid()
  where auth.uid() in (t.user_a, t.user_b)
  order by coalesce(lm.created_at, t.created_at) desc;
$$;

------------------------------------------------------------------------------
-- Realtime
------------------------------------------------------------------------------

do $$
declare
  tbl text;
begin
  foreach tbl in array array['dm_messages', 'dm_reactions'] loop
    if not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = tbl) then
      execute format('alter publication supabase_realtime add table public.%I', tbl);
    end if;
  end loop;
end $$;
