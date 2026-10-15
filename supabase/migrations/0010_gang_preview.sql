-- gng 0010: see a gang before you join it (from a QR code or invite link).
-- Run after 0009. Safe to run more than once.

-- Name, icon, photo and size of the gang with this invite code, and whether you're already in it.
-- Signed-in people only; reveals nothing a person holding the code couldn't see by joining.
drop function if exists public.gang_preview(text);
create function public.gang_preview(p_code text)
returns table (id uuid, name text, emoji text, photo_url text, member_count bigint, already_member boolean)
language sql stable security definer set search_path = public as $$
  select g.id, g.name, g.emoji, g.photo_url,
         (select count(*) from group_members m where m.group_id = g.id),
         exists (select 1 from group_members m where m.group_id = g.id and m.user_id = auth.uid())
  from groups g
  where auth.uid() is not null and g.kind = 'gang' and g.invite_code = upper(trim(p_code));
$$;
