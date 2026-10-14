-- gng 0006: send new messages and calls to the `push` Edge Function.
-- Run after 0005. Safe to run more than once.
--
-- Does the same job as Database Webhooks in the dashboard, but lives in the repo.
-- The function URL and the shared secret are read from Supabase Vault, so neither is in git.
-- Until both Vault secrets exist (see docs/SETUP.md) the triggers quietly do nothing.
--
--   select vault.create_secret('https://<project-ref>.supabase.co/functions/v1/push', 'push_function_url');
--   select vault.create_secret('<same value as the function''s WEBHOOK_SECRET>', 'push_webhook_secret');

create extension if not exists pg_net with schema extensions;

create or replace function public.notify_push() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  url text;
  secret text;
begin
  select decrypted_secret into url from vault.decrypted_secrets where name = 'push_function_url';
  select decrypted_secret into secret from vault.decrypted_secrets where name = 'push_webhook_secret';
  if url is null or secret is null then return null; end if;
  -- Same payload shape as a Database Webhook. pg_net sends it after the transaction commits.
  perform net.http_post(
    url := url,
    body := jsonb_build_object(
      'type', tg_op,
      'table', tg_table_name,
      'record', to_jsonb(new),
      'old_record', case when tg_op = 'UPDATE' then to_jsonb(old) end),
    headers := jsonb_build_object('Content-Type', 'application/json', 'x-webhook-secret', secret));
  return null;
end;
$$;
revoke execute on function public.notify_push() from public, anon, authenticated;

drop trigger if exists push_messages on public.messages;
create trigger push_messages after insert on public.messages
  for each row execute function public.notify_push();

drop trigger if exists push_calls on public.calls;
create trigger push_calls after insert or update of ended_at on public.calls
  for each row execute function public.notify_push();
