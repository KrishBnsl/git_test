-- gng 0012: one chat entry per call, and missed calls.
-- Run after 0011. Safe to run more than once.
--
-- Before: "Aman started a voice call" and later a separate "Voice call ended · 3 min".
-- Now the first one is updated in place when the call ends: "Voice call · 3 min", or
-- "Missed voice call" if nobody picked up. Phones mark a call answered when it connects.

alter table public.calls add column if not exists answered_at timestamptz;

create or replace function public.mark_call_answered(p_call uuid) returns void
language sql security definer set search_path = public as $$
  update calls set answered_at = coalesce(answered_at, now())
  where id = p_call and ended_at is null and public.is_member(group_id);
$$;

create or replace function public.end_call(p_call uuid) returns void
language plpgsql security definer set search_path = public as $$
declare
  c public.calls;
  label text;
  mins int;
  updated int;
begin
  update calls set ended_at = now()
  where id = p_call and ended_at is null and public.is_member(group_id)
  returning * into c;
  if c.id is null then return; end if;
  label := case when c.video then 'video' else 'voice' end;
  mins := greatest(1, ceil(extract(epoch from (c.ended_at - coalesce(c.answered_at, c.started_at))) / 60.0)::int);
  -- edited_at tells phones that were offline to pick up the change.
  update messages
     set body = case when c.answered_at is null then 'Missed ' || label || ' call'
                     else initcap(label) || ' call · ' || mins || ' min' end,
         edited_at = now()
   where kind = 'call' and ref_id = c.id;
  get diagnostics updated = row_count;
  if updated = 0 then
    insert into messages (group_id, user_id, body, kind, ref_id)
    values (c.group_id, null, initcap(label) || ' call · ' || mins || ' min', 'call', c.id);
  end if;
end;
$$;

------------------------------------------------------------------------------
-- "typing…": a private Realtime channel per conversation ("typing:<group id>"), members only.
-- Nothing is stored; it only carries "this person is typing" while they type.
------------------------------------------------------------------------------

drop policy if exists "members hear typing" on realtime.messages;
create policy "members hear typing" on realtime.messages for select to authenticated using (
  realtime.topic() like 'typing:%' and public.is_member_folder(split_part(realtime.topic(), ':', 2)));
drop policy if exists "members send typing" on realtime.messages;
create policy "members send typing" on realtime.messages for insert to authenticated with check (
  realtime.topic() like 'typing:%' and public.is_member_folder(split_part(realtime.topic(), ':', 2)));
