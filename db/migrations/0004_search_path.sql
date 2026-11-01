-- 0004 SECURITY DEFINER 함수의 search_path 고정.
-- Supabase에서는 pgcrypto가 extensions 스키마에 있고, 호출자의 search_path가 함수 안으로 들어오므로 고정한다.
-- 로컬 Postgres에는 extensions 스키마가 없지만 search_path에 없는 스키마는 무시되므로 같은 문장이 양쪽에서 돈다.

ALTER FUNCTION lifelog.register_device(text, text, text) SET search_path = pg_catalog, lifelog, public, extensions;
ALTER FUNCTION lifelog.revoke_device(text) SET search_path = pg_catalog, lifelog, public, extensions;
ALTER FUNCTION lifelog.register_plugin(jsonb, text) SET search_path = pg_catalog, lifelog, public, extensions;
ALTER FUNCTION lifelog.set_install(text, text, text, boolean, jsonb, jsonb) SET search_path = pg_catalog, lifelog, public, extensions;
ALTER FUNCTION lifelog.reset_device_password(text) SET search_path = pg_catalog, lifelog, public, extensions;
ALTER FUNCTION lifelog.regen_view(text, text) SET search_path = pg_catalog, lifelog, public, extensions;
