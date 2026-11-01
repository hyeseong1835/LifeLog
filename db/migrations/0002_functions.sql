-- 0002 등록 함수: register_device, revoke_device, register_plugin, set_install.
-- 근거: Documents/등록 함수.md, Documents/매니페스트.md, Documents/인증·권한.md.
-- 전부 SECURITY DEFINER이며 마이그레이션 실행 롤이 소유한다. 거부 메시지는 한국어.

-- 유틸 ---------------------------------------------------------------------

-- semver 'a.b.c' → int[]
CREATE FUNCTION lifelog.semver(v text) RETURNS integer[]
LANGUAGE sql IMMUTABLE AS $$
  SELECT ARRAY[split_part(v,'.',1)::int, split_part(v,'.',2)::int, split_part(v,'.',3)::int]
$$;

-- 매니페스트 settings/privacy 항목 하나의 값이 타입에 맞는지
CREATE FUNCTION lifelog.setting_value_ok(item jsonb, val jsonb) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE t text := item->>'type';
BEGIN
  IF val IS NULL THEN RETURN false; END IF;
  RETURN CASE t
    WHEN 'string'   THEN jsonb_typeof(val) = 'string'
    WHEN 'number'   THEN jsonb_typeof(val) = 'number'
    WHEN 'boolean'  THEN jsonb_typeof(val) = 'boolean'
    WHEN 'duration' THEN jsonb_typeof(val) = 'string' AND (val #>> '{}') ~ '^[0-9]+(ms|s|m|h|d)$'
    WHEN 'enum'     THEN jsonb_typeof(val) = 'string' AND (item->'options') ? (val #>> '{}')
    WHEN 'list'     THEN jsonb_typeof(val) = 'array'
                         AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(val) e WHERE jsonb_typeof(e) <> 'string')
    ELSE false END;
END $$;

-- settings/privacy 선언 검증 (등록 시)
CREATE FUNCTION lifelog.validate_setting_decls(decls jsonb, what text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE item jsonb; k text;
BEGIN
  IF decls IS NULL THEN RETURN; END IF;
  IF jsonb_typeof(decls) <> 'array' THEN
    RAISE EXCEPTION '매니페스트 %는 배열이어야 합니다', what;
  END IF;
  FOR item IN SELECT * FROM jsonb_array_elements(decls) LOOP
    k := item->>'key';
    IF k IS NULL OR k !~ '^[a-z][a-z0-9_]{0,40}$' THEN
      RAISE EXCEPTION '% 항목의 key가 잘못됐습니다: %', what, COALESCE(k, '(없음)');
    END IF;
    IF (item->>'type') IS NULL OR (item->>'type') NOT IN ('string','number','boolean','duration','enum','list') THEN
      RAISE EXCEPTION '% 항목 %의 type이 잘못됐습니다: %', what, k, item->>'type';
    END IF;
    IF (item->>'type') = 'enum' AND (item->'options' IS NULL OR jsonb_typeof(item->'options') <> 'array') THEN
      RAISE EXCEPTION '% 항목 %: enum에는 options 배열이 필요합니다', what, k;
    END IF;
    IF item->'default' IS NULL OR NOT lifelog.setting_value_ok(item, item->'default') THEN
      RAISE EXCEPTION '% 항목 %의 default가 타입 %에 맞지 않습니다', what, k, item->>'type';
    END IF;
    IF (item->>'label') IS NULL THEN
      RAISE EXCEPTION '% 항목 %에 label이 없습니다', what, k;
    END IF;
  END LOOP;
END $$;

-- capabilities 문법
CREATE FUNCTION lifelog.validate_capabilities(caps jsonb) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE c jsonb; id text;
BEGIN
  IF caps IS NULL THEN RETURN; END IF;
  IF jsonb_typeof(caps) <> 'array' THEN RAISE EXCEPTION 'capabilities는 배열이어야 합니다'; END IF;
  FOR c IN SELECT * FROM jsonb_array_elements(caps) LOOP
    id := c->>'id';
    IF id IS NULL OR id !~ '^(http:[A-Za-z0-9.*-]+|file:(read|write):.+|sqlite:.+|process|registry:.+|event:[a-z][a-z0-9_]*|read:[a-z][a-z0-9_]*|secret:[a-z][a-z0-9_]*|clock|random|timer)$' THEN
      RAISE EXCEPTION 'capabilities id가 잘못됐습니다: %', COALESCE(id, '(없음)');
    END IF;
    IF c ? 'required' AND jsonb_typeof(c->'required') <> 'boolean' THEN
      RAISE EXCEPTION 'capabilities %의 required는 boolean이어야 합니다', id;
    END IF;
  END LOOP;
END $$;

-- 허용 키 밖의 항목이 있으면 거부 (x- 접두는 통과)
CREATE FUNCTION lifelog.reject_unknown_keys(obj jsonb, allowed text[], what text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE k text;
BEGIN
  FOR k IN SELECT jsonb_object_keys(obj) LOOP
    IF NOT (k = ANY(allowed)) AND k NOT LIKE 'x-%' THEN
      RAISE EXCEPTION '%에 모르는 항목이 있습니다: %', what, k;
    END IF;
  END LOOP;
END $$;

-- 뷰 재생성: 플러그인/출력의 모든 schema_version을 UNION ALL -------------------
CREATE FUNCTION lifelog.regen_view(p_plugin text, p_output text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
  view_name text := p_plugin || '/' || p_output;
  v_kind text;
  cols jsonb := '{}'::jsonb;         -- 열 이름 → 타입 (합집합)
  rec record;
  col record;
  selects text[] := '{}';
  sel text;
  common text;
  k text;
BEGIN
  SELECT kind INTO v_kind FROM lifelog.collector_tables WHERE plugin_id = p_plugin AND output = p_output LIMIT 1;
  IF v_kind IS NULL THEN RETURN; END IF;
  common := CASE v_kind WHEN 'span' THEN 'device, id, start, "end", updated_at' ELSE 'device, id, time, updated_at' END;

  -- 합집합 열. 같은 이름은 타입이 같아야 한다(등록 시 이미 검증).
  FOR rec IN SELECT columns FROM lifelog.collector_tables WHERE plugin_id = p_plugin AND output = p_output ORDER BY schema_version LOOP
    cols := cols || rec.columns;
  END LOOP;

  FOR rec IN SELECT schema_version, table_name, columns FROM lifelog.collector_tables
             WHERE plugin_id = p_plugin AND output = p_output ORDER BY schema_version LOOP
    sel := 'SELECT ' || common;
    FOR k IN SELECT key FROM jsonb_each_text(cols) ORDER BY key LOOP
      IF rec.columns ? k THEN
        sel := sel || ', ' || quote_ident(k);
      ELSE
        sel := sel || ', NULL::' || (cols->>k) || ' AS ' || quote_ident(k);
      END IF;
    END LOOP;
    sel := sel || ', ' || rec.schema_version || '::int AS schema_version FROM lifelog.' || quote_ident(rec.table_name);
    selects := selects || sel;
  END LOOP;

  EXECUTE format('DROP VIEW IF EXISTS lifelog.%I', view_name);
  EXECUTE format('CREATE VIEW lifelog.%I AS %s', view_name, array_to_string(selects, ' UNION ALL '));
  EXECUTE format('GRANT SELECT ON lifelog.%I TO lifelog_devices, lifelog_reader', view_name);
END $$;

-- register_device -----------------------------------------------------------
-- 관리자가 호출. 기기 롤을 만들고 비밀번호를 한 번만 돌려준다.
CREATE FUNCTION lifelog.register_device(p_id text, p_name text, p_platform text) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  role_name text := 'lifelog_device_' || p_id;
  pw text := encode(gen_random_bytes(24), 'base64');
  v text;
BEGIN
  IF p_id !~ '^[a-z][a-z0-9-]{1,40}$' THEN
    RAISE EXCEPTION '기기 id는 ^[a-z][a-z0-9-]{1,40}$ 꼴이어야 합니다: %', p_id;
  END IF;
  IF EXISTS (SELECT 1 FROM lifelog.devices WHERE id = p_id AND revoked_at IS NULL) THEN
    RAISE EXCEPTION '이미 등록된 기기입니다: %. 비밀번호를 다시 받으려면 폐기 후 새 id로 등록하세요', p_id;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
    RAISE EXCEPTION '롤 %가 이미 있습니다. revoke_device로 정리하세요', role_name;
  END IF;

  EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L IN ROLE lifelog_devices', role_name, pw);
  INSERT INTO lifelog.devices (id, name, platform) VALUES (p_id, p_name, p_platform)
    ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, platform = EXCLUDED.platform, revoked_at = NULL, first_seen = now();
  -- 이 기기가 등록한 출력 테이블의 쓰기 권한은 register_plugin이 준다. 뷰 SELECT는 lifelog_devices 그룹으로.
  RETURN pw;
END $$;
REVOKE ALL ON FUNCTION lifelog.register_device(text, text, text) FROM PUBLIC;

-- revoke_device -------------------------------------------------------------
CREATE FUNCTION lifelog.revoke_device(p_id text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE role_name text := 'lifelog_device_' || p_id;
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
    EXECUTE format('REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA lifelog FROM %I', role_name);
    EXECUTE format('REVOKE ALL PRIVILEGES ON SCHEMA lifelog FROM %I', role_name);
    EXECUTE format('DROP OWNED BY %I', role_name);
    EXECUTE format('DROP ROLE %I', role_name);
  END IF;
  UPDATE lifelog.devices SET revoked_at = now() WHERE id = p_id;
END $$;
REVOKE ALL ON FUNCTION lifelog.revoke_device(text) FROM PUBLIC;

-- register_plugin -----------------------------------------------------------
CREATE FUNCTION lifelog.register_plugin(m jsonb, p_device text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  caller_device text := lifelog.current_device();
  p_type text := m->>'type';
  p_id text := m->>'id';
  p_version text := m->>'version';
  rt jsonb := m->'runtimes';
  rt_key text;
  out_item jsonb;
  out_name text;
  out_kind text;
  sv integer;
  cols jsonb;
  col_name text;
  col_type text;
  tbl text;
  existing jsonb;
  other record;
  ddl text;
  role_name text := 'lifelog_device_' || p_device;
  created text[] := '{}';
  input text;
  defaults jsonb := '{}'::jsonb;
  item jsonb;
BEGIN
  -- 호출자 확인: 기기 롤이면 자기 기기여야 한다. 관리자는 아무 기기나 대신 등록할 수 있다.
  IF caller_device IS NOT NULL AND caller_device <> p_device THEN
    RAISE EXCEPTION '다른 기기(%)의 이름으로 등록할 수 없습니다', p_device;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM lifelog.devices WHERE id = p_device AND revoked_at IS NULL) THEN
    RAISE EXCEPTION '등록되지 않은 기기입니다: %', p_device;
  END IF;

  -- 구조
  IF jsonb_typeof(m) <> 'object' THEN RAISE EXCEPTION '매니페스트는 객체여야 합니다'; END IF;
  PERFORM lifelog.reject_unknown_keys(m, ARRAY['type','id','version','name','description','min_host_version','runtimes',
    'capabilities','settings','homepage','license','outputs','privacy','inputs','slot','depends'], '매니페스트');
  IF p_type IS NULL OR p_type NOT IN ('collector','renderer') THEN
    RAISE EXCEPTION 'type은 collector 또는 renderer여야 합니다: %', COALESCE(p_type,'(없음)');
  END IF;
  IF p_id IS NULL OR p_id !~ '^[a-z][a-z0-9-]{1,40}$' THEN
    RAISE EXCEPTION 'id는 ^[a-z][a-z0-9-]{1,40}$ 꼴이어야 합니다: %', COALESCE(p_id,'(없음)');
  END IF;
  IF p_version IS NULL OR p_version !~ '^[0-9]+\.[0-9]+\.[0-9]+$' THEN
    RAISE EXCEPTION 'version은 semver(MAJOR.MINOR.PATCH)여야 합니다: %', COALESCE(p_version,'(없음)');
  END IF;
  IF (m->>'name') IS NULL THEN RAISE EXCEPTION 'name이 없습니다'; END IF;
  IF jsonb_typeof(m->'min_host_version') <> 'number' OR (m->>'min_host_version')::numeric < 1
     OR (m->>'min_host_version')::numeric <> floor((m->>'min_host_version')::numeric) THEN
    RAISE EXCEPTION 'min_host_version은 1 이상의 정수여야 합니다';
  END IF;
  IF rt IS NULL OR jsonb_typeof(rt) <> 'object' OR (SELECT count(*) FROM jsonb_object_keys(rt)) <> 1 THEN
    RAISE EXCEPTION 'runtimes는 키 하나(js | lua | webview)인 객체여야 합니다';
  END IF;
  SELECT key INTO rt_key FROM jsonb_object_keys(rt) key;
  IF rt_key NOT IN ('js','lua','webview') THEN RAISE EXCEPTION '모르는 런타임입니다: %', rt_key; END IF;
  IF jsonb_typeof(rt->rt_key) <> 'string' THEN RAISE EXCEPTION 'runtimes.%는 진입 파일 경로여야 합니다', rt_key; END IF;
  PERFORM lifelog.validate_capabilities(m->'capabilities');
  PERFORM lifelog.validate_setting_decls(m->'settings', 'settings');

  -- 기존 플러그인과 type이 다르면 거부
  IF EXISTS (SELECT 1 FROM lifelog.plugins WHERE id = p_id AND type <> p_type) THEN
    RAISE EXCEPTION '플러그인 %는 이미 다른 type으로 등록돼 있습니다', p_id;
  END IF;
  -- 같은 버전이 다른 내용으로 등록돼 있으면 거부(멱등성: 같으면 통과)
  SELECT manifest INTO existing FROM lifelog.plugin_versions WHERE plugin_id = p_id AND version = p_version;
  IF existing IS NOT NULL AND existing <> m THEN
    RAISE EXCEPTION '플러그인 % 버전 %가 다른 내용으로 이미 등록돼 있습니다. 버전을 올리세요', p_id, p_version;
  END IF;

  IF p_type = 'collector' THEN
    IF rt_key NOT IN ('js','lua') THEN RAISE EXCEPTION '수집기의 런타임은 js 또는 lua여야 합니다'; END IF;
    IF m ? 'inputs' OR m ? 'slot' OR m ? 'depends' THEN RAISE EXCEPTION '수집기 매니페스트에는 inputs·slot·depends를 쓸 수 없습니다'; END IF;
    IF jsonb_typeof(m->'outputs') <> 'array' OR jsonb_array_length(m->'outputs') < 1 THEN
      RAISE EXCEPTION '수집기는 outputs가 1개 이상 필요합니다';
    END IF;
    PERFORM lifelog.validate_setting_decls(m->'privacy', 'privacy');
    IF (SELECT count(DISTINCT o->>'name') FROM jsonb_array_elements(m->'outputs') o) <> jsonb_array_length(m->'outputs') THEN
      RAISE EXCEPTION 'outputs의 name이 겹칩니다';
    END IF;
  ELSE
    IF rt_key <> 'webview' THEN RAISE EXCEPTION '렌더러의 런타임은 webview만 됩니다'; END IF;
    IF m ? 'outputs' OR m ? 'privacy' THEN RAISE EXCEPTION '렌더러 매니페스트에는 outputs·privacy를 쓸 수 없습니다'; END IF;
    IF jsonb_typeof(m->'inputs') <> 'array' THEN RAISE EXCEPTION '렌더러는 inputs 배열이 필요합니다'; END IF;
    FOR input IN SELECT jsonb_array_elements_text(m->'inputs') LOOP
      IF input !~ '^[a-z][a-z0-9-]+/[a-z][a-z0-9_]*@[0-9]+$' THEN
        RAISE EXCEPTION 'inputs 항목은 수집기/출력@schema_version 꼴이어야 합니다: %', input;
      END IF;
    END LOOP;
    IF (m->>'slot') IS NULL OR (m->>'slot') NOT IN ('lane','card','page') THEN
      RAISE EXCEPTION 'slot은 lane | card | page여야 합니다';
    END IF;
    IF m ? 'depends' THEN
      IF jsonb_typeof(m->'depends') <> 'object' THEN RAISE EXCEPTION 'depends는 객체여야 합니다'; END IF;
      FOR input IN SELECT value FROM jsonb_each_text(m->'depends') LOOP
        IF input !~ '^(>=[0-9]+\.[0-9]+\.[0-9]+|\^[0-9]+\.[0-9]+|[0-9]+\.x)$' THEN
          RAISE EXCEPTION 'depends의 버전 범위는 >=a.b.c, ^a.b, a.x 중 하나여야 합니다: %', input;
        END IF;
      END LOOP;
    END IF;
  END IF;

  -- 등록부
  INSERT INTO lifelog.plugins (id, type, latest_version) VALUES (p_id, p_type, p_version)
    ON CONFLICT (id) DO UPDATE SET latest_version =
      CASE WHEN lifelog.semver(EXCLUDED.latest_version) > lifelog.semver(lifelog.plugins.latest_version)
           THEN EXCLUDED.latest_version ELSE lifelog.plugins.latest_version END;
  INSERT INTO lifelog.plugin_versions (plugin_id, version, manifest, registered_by) VALUES (p_id, p_version, m, p_device)
    ON CONFLICT DO NOTHING;

  -- 수집기 출력 테이블
  IF p_type = 'collector' THEN
    FOR out_item IN SELECT * FROM jsonb_array_elements(m->'outputs') LOOP
      PERFORM lifelog.reject_unknown_keys(out_item, ARRAY['name','kind','schema_version','columns','label','identity','index'], 'outputs 항목');
      out_name := out_item->>'name';
      out_kind := out_item->>'kind';
      IF out_name IS NULL OR out_name !~ '^[a-z][a-z0-9_]{0,30}$' THEN
        RAISE EXCEPTION 'outputs.name이 잘못됐습니다: %', COALESCE(out_name,'(없음)');
      END IF;
      IF out_kind IS NULL OR out_kind NOT IN ('span','event') THEN
        RAISE EXCEPTION '출력 %의 kind는 span 또는 event여야 합니다', out_name;
      END IF;
      IF jsonb_typeof(out_item->'schema_version') <> 'number' OR (out_item->>'schema_version')::numeric < 1
         OR (out_item->>'schema_version')::numeric <> floor((out_item->>'schema_version')::numeric) THEN
        RAISE EXCEPTION '출력 %의 schema_version은 1 이상의 정수여야 합니다', out_name;
      END IF;
      sv := (out_item->>'schema_version')::int;
      cols := out_item->'columns';
      IF cols IS NULL OR jsonb_typeof(cols) <> 'object' OR cols = '{}'::jsonb THEN
        RAISE EXCEPTION '출력 %에 columns가 없습니다', out_name;
      END IF;
      FOR col_name, col_type IN SELECT key, value FROM jsonb_each_text(cols) LOOP
        IF col_name !~ '^[a-z][a-z0-9_]{0,40}$' THEN
          RAISE EXCEPTION '출력 %의 열 이름이 잘못됐습니다: %', out_name, col_name;
        END IF;
        IF col_name IN ('device','id','start','end','time','updated_at','schema_version') THEN
          RAISE EXCEPTION '출력 %의 열 %는 공통 열과 겹칩니다', out_name, col_name;
        END IF;
        IF EXISTS (SELECT 1 FROM pg_get_keywords() WHERE word = col_name AND catcode IN ('R','T')) THEN
          RAISE EXCEPTION '출력 %의 열 %는 SQL 예약어입니다', out_name, col_name;
        END IF;
        IF col_type NOT IN ('text','boolean','integer','bigint','double precision','numeric','timestamptz','date','jsonb','text[]') THEN
          RAISE EXCEPTION '출력 %의 열 %의 타입 %는 허용되지 않습니다', out_name, col_name, col_type;
        END IF;
      END LOOP;
      IF (out_item->>'label') IS NULL OR NOT (cols ? (out_item->>'label')) THEN
        RAISE EXCEPTION '출력 %의 label은 columns에 있는 열이어야 합니다', out_name;
      END IF;
      IF out_item ? 'identity' AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(out_item->'identity') c WHERE NOT (cols ? c)) THEN
        RAISE EXCEPTION '출력 %의 identity에 columns에 없는 열이 있습니다', out_name;
      END IF;
      IF out_item ? 'index' AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(out_item->'index') c WHERE NOT (cols ? c)) THEN
        RAISE EXCEPTION '출력 %의 index에 columns에 없는 열이 있습니다', out_name;
      END IF;

      tbl := p_id || '/' || out_name || '@' || sv;
      IF octet_length(tbl) > 63 THEN
        RAISE EXCEPTION '테이블 이름 %가 63바이트를 넘습니다. id나 출력 이름을 줄이세요', tbl;
      END IF;

      -- 같은 버전 테이블이 있으면 열 집합이 같아야 한다
      SELECT columns INTO existing FROM lifelog.collector_tables
        WHERE plugin_id = p_id AND output = out_name AND schema_version = sv;
      IF existing IS NOT NULL THEN
        IF existing <> cols THEN
          RAISE EXCEPTION '출력 %의 schema_version %는 이미 다른 열 집합으로 있습니다. 열이 바뀌었으면 schema_version을 올리세요', out_name, sv;
        END IF;
        IF (SELECT kind FROM lifelog.collector_tables WHERE plugin_id = p_id AND output = out_name AND schema_version = sv) <> out_kind THEN
          RAISE EXCEPTION '출력 %의 kind가 기존 테이블과 다릅니다', out_name;
        END IF;
        -- 다른 기기의 등록: 권한만 추가
        EXECUTE format('GRANT INSERT, UPDATE, DELETE ON lifelog.%I TO %I', tbl, role_name);
        CONTINUE;
      END IF;

      -- 다른 버전과 같은 이름의 열은 타입이 같아야 한다
      FOR other IN SELECT schema_version AS v, columns AS c FROM lifelog.collector_tables WHERE plugin_id = p_id AND output = out_name LOOP
        IF other.c->>'kind' IS NOT NULL THEN NULL; END IF;
        FOR col_name, col_type IN SELECT key, value FROM jsonb_each_text(cols) LOOP
          IF other.c ? col_name AND (other.c->>col_name) <> col_type THEN
            RAISE EXCEPTION '출력 %의 열 %는 schema_version %에서 타입이 %였습니다. 타입을 바꾸려면 새 이름을 쓰세요', out_name, col_name, other.v, other.c->>col_name;
          END IF;
        END LOOP;
        IF EXISTS (SELECT 1 FROM lifelog.collector_tables WHERE plugin_id = p_id AND output = out_name AND kind <> out_kind) THEN
          RAISE EXCEPTION '출력 %의 kind는 버전 사이에서 바꿀 수 없습니다', out_name;
        END IF;
      END LOOP;

      -- 테이블 생성
      ddl := format('CREATE TABLE lifelog.%I (device text NOT NULL REFERENCES lifelog.devices(id), id uuid NOT NULL, ', tbl);
      IF out_kind = 'span' THEN
        ddl := ddl || 'start timestamptz NOT NULL, "end" timestamptz NOT NULL CHECK ("end" >= start), ';
      ELSE
        ddl := ddl || 'time timestamptz NOT NULL, ';
      END IF;
      ddl := ddl || 'updated_at timestamptz NOT NULL DEFAULT now()';
      FOR col_name, col_type IN SELECT key, value FROM jsonb_each_text(cols) ORDER BY key LOOP
        ddl := ddl || ', ' || quote_ident(col_name) || ' ' || col_type;
      END LOOP;
      ddl := ddl || ', PRIMARY KEY (device, id))';
      EXECUTE ddl;
      EXECUTE format('CREATE INDEX ON lifelog.%I (device, %s)', tbl, CASE out_kind WHEN 'span' THEN 'start' ELSE 'time' END);
      IF out_item ? 'index' THEN
        FOR col_name IN SELECT jsonb_array_elements_text(out_item->'index') LOOP
          EXECUTE format('CREATE INDEX ON lifelog.%I (%I)', tbl, col_name);
        END LOOP;
      END IF;
      EXECUTE format('CREATE TRIGGER touch_updated_at BEFORE INSERT OR UPDATE ON lifelog.%I FOR EACH ROW EXECUTE FUNCTION lifelog.touch_updated_at()', tbl);
      -- RLS: 쓰기는 자기 기기 행만, 읽기는 전부
      EXECUTE format('ALTER TABLE lifelog.%I ENABLE ROW LEVEL SECURITY', tbl);
      EXECUTE format('CREATE POLICY read_all ON lifelog.%I FOR SELECT USING (true)', tbl);
      EXECUTE format('CREATE POLICY own_device_write ON lifelog.%I FOR ALL USING (device = lifelog.current_device()) WITH CHECK (device = lifelog.current_device())', tbl);
      EXECUTE format('GRANT SELECT ON lifelog.%I TO lifelog_devices, lifelog_reader', tbl);
      EXECUTE format('GRANT INSERT, UPDATE, DELETE ON lifelog.%I TO %I', tbl, role_name);

      INSERT INTO lifelog.collector_tables (plugin_id, output, schema_version, table_name, kind, columns)
        VALUES (p_id, out_name, sv, tbl, out_kind, cols);
      PERFORM lifelog.regen_view(p_id, out_name);
      created := created || tbl;
    END LOOP;
  END IF;

  -- 기본 설정값으로 plugin_installs 행을 만든다(없을 때만). enabled는 false로 시작한다.
  FOR item IN SELECT * FROM jsonb_array_elements(COALESCE(m->'settings','[]'::jsonb) || COALESCE(m->'privacy','[]'::jsonb)) LOOP
    defaults := defaults || jsonb_build_object(item->>'key', item->'default');
  END LOOP;
  INSERT INTO lifelog.plugin_installs (plugin_id, device, installed_version, enabled, settings)
    VALUES (p_id, p_device, p_version, false, defaults)
    ON CONFLICT (plugin_id, device) DO NOTHING;

  RETURN jsonb_build_object('plugin', p_id, 'version', p_version, 'type', p_type, 'created_tables', to_jsonb(created));
END $$;
REVOKE ALL ON FUNCTION lifelog.register_plugin(jsonb, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION lifelog.register_plugin(jsonb, text) TO lifelog_devices;

-- set_install ---------------------------------------------------------------
CREATE FUNCTION lifelog.set_install(p_plugin text, p_device text, p_version text, p_enabled boolean, p_settings jsonb, p_granted jsonb)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  caller_device text := lifelog.current_device();
  m jsonb;
  decls jsonb;
  item jsonb;
  k text;
  merged jsonb := '{}'::jsonb;
  g text;
  cap jsonb;
  enabled boolean := COALESCE(p_enabled, false);
  unmet jsonb := '[]'::jsonb;
  input text;
  row_out jsonb;
BEGIN
  IF caller_device IS NOT NULL AND caller_device <> p_device THEN
    RAISE EXCEPTION '다른 기기(%)의 설치 상태를 바꿀 수 없습니다', p_device;
  END IF;
  SELECT manifest INTO m FROM lifelog.plugin_versions WHERE plugin_id = p_plugin AND version = p_version;
  IF m IS NULL THEN RAISE EXCEPTION '플러그인 % 버전 %가 등록돼 있지 않습니다', p_plugin, p_version; END IF;

  -- settings: 선언된 키만, 타입 맞게, 빠진 키는 기본값
  decls := COALESCE(m->'settings','[]'::jsonb) || COALESCE(m->'privacy','[]'::jsonb);
  IF p_settings IS NOT NULL THEN
    IF jsonb_typeof(p_settings) <> 'object' THEN RAISE EXCEPTION 'settings는 객체여야 합니다'; END IF;
    FOR k IN SELECT jsonb_object_keys(p_settings) LOOP
      IF NOT EXISTS (SELECT 1 FROM jsonb_array_elements(decls) d WHERE d->>'key' = k) THEN
        RAISE EXCEPTION '플러그인 %에 없는 설정 키입니다: %', p_plugin, k;
      END IF;
    END LOOP;
  END IF;
  FOR item IN SELECT * FROM jsonb_array_elements(decls) LOOP
    k := item->>'key';
    IF p_settings IS NOT NULL AND p_settings ? k THEN
      IF NOT lifelog.setting_value_ok(item, p_settings->k) THEN
        RAISE EXCEPTION '설정 %의 값이 타입 %에 맞지 않습니다', k, item->>'type';
      END IF;
      merged := merged || jsonb_build_object(k, p_settings->k);
    ELSE
      merged := merged || jsonb_build_object(k, item->'default');
    END IF;
  END LOOP;

  -- granted: 매니페스트에 있는 권한만. 필수 권한이 빠지면 enabled=false
  IF p_granted IS NULL THEN p_granted := '[]'::jsonb; END IF;
  IF jsonb_typeof(p_granted) <> 'array' THEN RAISE EXCEPTION 'granted는 배열이어야 합니다'; END IF;
  FOR g IN SELECT jsonb_array_elements_text(p_granted) LOOP
    IF NOT EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE(m->'capabilities','[]'::jsonb)) c WHERE c->>'id' = g) THEN
      RAISE EXCEPTION '매니페스트에 없는 권한을 허용할 수 없습니다: %', g;
    END IF;
  END LOOP;
  FOR cap IN SELECT * FROM jsonb_array_elements(COALESCE(m->'capabilities','[]'::jsonb)) LOOP
    IF COALESCE((cap->>'required')::boolean, false) AND NOT (p_granted ? (cap->>'id')) THEN
      enabled := false;
    END IF;
  END LOOP;

  -- 렌더러 입력 충족 여부
  IF (m->>'type') = 'renderer' THEN
    FOR input IN SELECT jsonb_array_elements_text(m->'inputs') LOOP
      IF NOT EXISTS (SELECT 1 FROM lifelog.collector_tables WHERE table_name = input) THEN
        unmet := unmet || to_jsonb(input);
      END IF;
    END LOOP;
  END IF;

  INSERT INTO lifelog.plugin_installs (plugin_id, device, installed_version, enabled, settings, granted, unmet)
    VALUES (p_plugin, p_device, p_version, enabled, merged, p_granted, unmet)
    ON CONFLICT (plugin_id, device) DO UPDATE
      SET installed_version = EXCLUDED.installed_version, enabled = EXCLUDED.enabled,
          settings = EXCLUDED.settings, granted = EXCLUDED.granted, unmet = EXCLUDED.unmet;

  SELECT to_jsonb(pi) INTO row_out FROM lifelog.plugin_installs pi WHERE plugin_id = p_plugin AND device = p_device;
  RETURN row_out;
END $$;
REVOKE ALL ON FUNCTION lifelog.set_install(text, text, text, boolean, jsonb, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION lifelog.set_install(text, text, text, boolean, jsonb, jsonb) TO lifelog_devices;
