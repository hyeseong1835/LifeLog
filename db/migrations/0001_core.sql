-- 0001 코어 스키마: 스키마, 롤, 등록부 테이블, RLS, 공통 트리거.
-- 근거: Documents/테이블 설계.md 4절, Documents/인증·권한.md.
-- 모든 객체는 마이그레이션을 실행하는 롤(= lifelog_admin 역할)이 소유한다.

CREATE SCHEMA IF NOT EXISTS lifelog;
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_bytes (기기 롤 비밀번호)

-- 롤 --------------------------------------------------------------------
-- lifelog_devices: 모든 기기 롤이 속하는 그룹. 뷰 SELECT 등 공통 권한은 여기에 준다.
-- lifelog_reader : 읽기 전용(Claude MCP 등). 비밀번호는 관리자가 따로 준다.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifelog_devices') THEN
    CREATE ROLE lifelog_devices NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifelog_reader') THEN
    CREATE ROLE lifelog_reader LOGIN;
  END IF;
END $$;

GRANT USAGE ON SCHEMA lifelog TO lifelog_devices, lifelog_reader;

-- 등록부 테이블 -----------------------------------------------------------
CREATE TABLE lifelog.schema_migrations (
  version     integer PRIMARY KEY,
  applied_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE lifelog.devices (
  id          text PRIMARY KEY CHECK (id ~ '^[a-z][a-z0-9-]{1,40}$'),
  name        text NOT NULL,
  platform    text NOT NULL,
  first_seen  timestamptz NOT NULL DEFAULT now(),
  last_seen   timestamptz,
  revoked_at  timestamptz,
  replaces    text REFERENCES lifelog.devices(id)
);

CREATE TABLE lifelog.plugins (
  id              text PRIMARY KEY CHECK (id ~ '^[a-z][a-z0-9-]{1,40}$'),
  type            text NOT NULL CHECK (type IN ('collector', 'renderer')),
  latest_version  text NOT NULL,
  source_url      text
);

CREATE TABLE lifelog.plugin_versions (
  plugin_id      text NOT NULL REFERENCES lifelog.plugins(id),
  version        text NOT NULL CHECK (version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
  manifest       jsonb NOT NULL,
  registered_at  timestamptz NOT NULL DEFAULT now(),
  registered_by  text REFERENCES lifelog.devices(id),
  PRIMARY KEY (plugin_id, version)
);

CREATE TABLE lifelog.plugin_installs (
  plugin_id          text NOT NULL REFERENCES lifelog.plugins(id),
  device             text NOT NULL REFERENCES lifelog.devices(id),
  installed_version  text NOT NULL,
  enabled            boolean NOT NULL DEFAULT false,
  settings           jsonb NOT NULL DEFAULT '{}'::jsonb,
  granted            jsonb NOT NULL DEFAULT '[]'::jsonb,
  unmet              jsonb NOT NULL DEFAULT '[]'::jsonb,
  last_seen          timestamptz,
  PRIMARY KEY (plugin_id, device),
  FOREIGN KEY (plugin_id, installed_version) REFERENCES lifelog.plugin_versions(plugin_id, version)
);

CREATE TABLE lifelog.collector_tables (
  plugin_id       text NOT NULL REFERENCES lifelog.plugins(id),
  output          text NOT NULL,
  schema_version  integer NOT NULL CHECK (schema_version >= 1),
  table_name      text NOT NULL UNIQUE,
  kind            text NOT NULL CHECK (kind IN ('span', 'event')),
  columns         jsonb NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (plugin_id, output, schema_version)
);

CREATE TABLE lifelog.plugin_state (
  plugin_id   text NOT NULL REFERENCES lifelog.plugins(id),
  device      text NOT NULL REFERENCES lifelog.devices(id),
  key         text NOT NULL,
  value       jsonb,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (plugin_id, device, key)
);

-- 공통 트리거: updated_at은 DB가 찍는다 ---------------------------------------
CREATE FUNCTION lifelog.touch_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END $$;

CREATE TRIGGER touch_updated_at BEFORE INSERT OR UPDATE ON lifelog.plugin_state
  FOR EACH ROW EXECUTE FUNCTION lifelog.touch_updated_at();

-- 접속한 롤의 기기 id. 기기 롤이 아니면 NULL.
-- session_user를 쓴다: SECURITY DEFINER 함수 안에서는 current_user가 함수 소유자로 바뀌기 때문.
CREATE FUNCTION lifelog.current_device() RETURNS text
LANGUAGE sql STABLE AS $$
  SELECT CASE WHEN session_user LIKE 'lifelog_device_%'
              THEN substr(session_user, length('lifelog_device_') + 1) END
$$;

-- 권한과 RLS ---------------------------------------------------------------
-- 등록부 읽기는 전부, 쓰기는 자기 기기 행만. 플러그인 등록·설치는 함수(SECURITY DEFINER)를 거친다.
GRANT SELECT ON ALL TABLES IN SCHEMA lifelog TO lifelog_devices, lifelog_reader;
GRANT UPDATE (last_seen) ON lifelog.devices TO lifelog_devices;
GRANT UPDATE (last_seen) ON lifelog.plugin_installs TO lifelog_devices;
GRANT INSERT, UPDATE, DELETE ON lifelog.plugin_state TO lifelog_devices;

ALTER TABLE lifelog.devices ENABLE ROW LEVEL SECURITY;
CREATE POLICY read_all ON lifelog.devices FOR SELECT USING (true);
CREATE POLICY own_row ON lifelog.devices FOR UPDATE
  USING (id = lifelog.current_device()) WITH CHECK (id = lifelog.current_device());

ALTER TABLE lifelog.plugin_installs ENABLE ROW LEVEL SECURITY;
CREATE POLICY read_all ON lifelog.plugin_installs FOR SELECT USING (true);
CREATE POLICY own_row ON lifelog.plugin_installs FOR UPDATE
  USING (device = lifelog.current_device()) WITH CHECK (device = lifelog.current_device());

ALTER TABLE lifelog.plugin_state ENABLE ROW LEVEL SECURITY;
CREATE POLICY read_all ON lifelog.plugin_state FOR SELECT USING (true);
CREATE POLICY own_row ON lifelog.plugin_state FOR ALL
  USING (device = lifelog.current_device()) WITH CHECK (device = lifelog.current_device());

-- 나머지 등록부(plugins, plugin_versions, collector_tables, schema_migrations)는 기기 롤이 쓰지 못한다.
