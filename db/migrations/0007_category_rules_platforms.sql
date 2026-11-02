-- 0007 분류 규칙 공유 + 매니페스트의 지원 플랫폼·외부 요구 사항.
--
-- 1) 앱 분류 규칙(컴퓨터 창 / 휴대폰 package)을 DB에 둔다. 모든 기기가 같은 규칙을 본다.
--    위젯 배치·위젯 설정·테마·하루 시작은 기기 로컬 파일에만 두고 DB에 올리지 않는다(Documents/대시보드 뷰·위젯.md).
--    쓰기는 set_category_rules()만. 규칙 목록 전체를 한 번에 바꾼다(편집 화면이 목록 단위로 저장한다).
-- 2) 매니페스트 키 `platforms`(이 플러그인이 도는 플랫폼)와 `requires`(따로 설치해야 하는 외부 프로그램·서비스).
--    register_plugin을 감싸 두 키를 검증한다. 같은 버전이 두 키만 다르게 다시 등록되면(0006 DB에서 두 키를 빼고 등록했던 경우)
--    거부하지 않고 저장된 매니페스트를 새 것으로 바꾼다.

-- 1. 분류 규칙 -------------------------------------------------------------------
CREATE TABLE lifelog.category_rules (
  kind        text PRIMARY KEY CHECK (kind IN ('pc', 'phone')),
  rules       jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(rules) = 'array'),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  updated_by  text REFERENCES lifelog.devices(id)
);
INSERT INTO lifelog.category_rules (kind) VALUES ('pc'), ('phone');
ALTER TABLE lifelog.category_rules ENABLE ROW LEVEL SECURITY;
CREATE POLICY read_all ON lifelog.category_rules FOR SELECT USING (true);
GRANT SELECT ON lifelog.category_rules TO lifelog_devices, lifelog_reader;

-- 규칙 한 줄: { name, app, title, color? }. name은 비지 않은 문자열("개발/VSCode"처럼 /로 계층), app·title은 정규식 문자열(빈 값 허용),
-- color는 #rrggbb. 검사는 모양만 한다(정규식 문법은 JS와 달라 여기서 보지 않는다).
CREATE FUNCTION lifelog.set_category_rules(p_kind text, p_rules jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, lifelog, public, extensions AS $$
DECLARE r jsonb; i int := 0;
BEGIN
  IF p_kind NOT IN ('pc', 'phone') THEN RAISE EXCEPTION 'kind는 pc 또는 phone입니다: %', p_kind; END IF;
  IF jsonb_typeof(p_rules) <> 'array' THEN RAISE EXCEPTION '규칙은 배열이어야 합니다'; END IF;
  IF jsonb_array_length(p_rules) > 2000 THEN RAISE EXCEPTION '규칙이 너무 많습니다(최대 2000)'; END IF;
  FOR r IN SELECT * FROM jsonb_array_elements(p_rules) LOOP
    i := i + 1;
    IF jsonb_typeof(r) <> 'object' THEN RAISE EXCEPTION '규칙 %번은 객체여야 합니다', i; END IF;
    PERFORM lifelog.reject_unknown_keys(r, ARRAY['name','app','title','color'], '분류 규칙');
    IF jsonb_typeof(r->'name') IS DISTINCT FROM 'string' OR length(r->>'name') = 0 OR length(r->>'name') > 200 THEN
      RAISE EXCEPTION '규칙 %번의 name은 1~200자 문자열이어야 합니다', i;
    END IF;
    IF r ? 'app' AND jsonb_typeof(r->'app') <> 'string' THEN RAISE EXCEPTION '규칙 %번의 app은 문자열이어야 합니다', i; END IF;
    IF r ? 'title' AND jsonb_typeof(r->'title') <> 'string' THEN RAISE EXCEPTION '규칙 %번의 title은 문자열이어야 합니다', i; END IF;
    IF r ? 'color' AND (jsonb_typeof(r->'color') <> 'string' OR (r->>'color') !~ '^#[0-9a-fA-F]{6}$') THEN
      RAISE EXCEPTION '규칙 %번의 color는 #rrggbb여야 합니다: %', i, r->>'color';
    END IF;
  END LOOP;
  UPDATE lifelog.category_rules SET rules = p_rules, updated_at = now(), updated_by = lifelog.current_device() WHERE kind = p_kind;
  RETURN (SELECT jsonb_build_object('kind', kind, 'rules', rules, 'updated_at', updated_at, 'updated_by', updated_by)
          FROM lifelog.category_rules WHERE kind = p_kind);
END $$;
REVOKE ALL ON FUNCTION lifelog.set_category_rules(text, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION lifelog.set_category_rules(text, jsonb) TO lifelog_devices;

-- 2. 매니페스트 platforms·requires ------------------------------------------------
-- platforms: ["windows", "android", ...] 중 하나 이상. 없으면 "플랫폼을 가리지 않음"(렌더러는 웹뷰라 보통 생략하거나 둘 다 적는다).
-- requires:  [{ "name": "VS Code 확장", "why": "...", "url": "https://...", "platforms": ["windows"] }]. 앱 밖에서 따로 설치·실행해야 하는 것.
CREATE FUNCTION lifelog.validate_platforms_requires(m jsonb) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE p text; r jsonb; known text[] := ARRAY['windows','android','macos','linux','ios'];
BEGIN
  IF m ? 'platforms' THEN
    IF jsonb_typeof(m->'platforms') <> 'array' OR jsonb_array_length(m->'platforms') = 0 THEN
      RAISE EXCEPTION 'platforms는 비지 않은 배열이어야 합니다';
    END IF;
    FOR p IN SELECT jsonb_array_elements_text(m->'platforms') LOOP
      IF NOT (p = ANY(known)) THEN RAISE EXCEPTION 'platforms의 모르는 값: % (windows | android | macos | linux | ios)', p; END IF;
    END LOOP;
  END IF;
  IF m ? 'requires' THEN
    IF jsonb_typeof(m->'requires') <> 'array' THEN RAISE EXCEPTION 'requires는 배열이어야 합니다'; END IF;
    FOR r IN SELECT * FROM jsonb_array_elements(m->'requires') LOOP
      IF jsonb_typeof(r) <> 'object' THEN RAISE EXCEPTION 'requires 항목은 객체여야 합니다'; END IF;
      PERFORM lifelog.reject_unknown_keys(r, ARRAY['name','why','url','platforms'], 'requires 항목');
      IF jsonb_typeof(r->'name') IS DISTINCT FROM 'string' OR length(r->>'name') = 0 THEN RAISE EXCEPTION 'requires 항목에 name이 없습니다'; END IF;
      IF r ? 'why' AND jsonb_typeof(r->'why') <> 'string' THEN RAISE EXCEPTION 'requires %의 why는 문자열이어야 합니다', r->>'name'; END IF;
      IF r ? 'url' AND (jsonb_typeof(r->'url') <> 'string' OR (r->>'url') !~ '^https?://') THEN RAISE EXCEPTION 'requires %의 url은 http(s) 주소여야 합니다', r->>'name'; END IF;
      IF r ? 'platforms' THEN
        IF jsonb_typeof(r->'platforms') <> 'array' THEN RAISE EXCEPTION 'requires %의 platforms는 배열이어야 합니다', r->>'name'; END IF;
        FOR p IN SELECT jsonb_array_elements_text(r->'platforms') LOOP
          IF NOT (p = ANY(known)) THEN RAISE EXCEPTION 'requires %의 platforms에 모르는 값: %', r->>'name', p; END IF;
        END LOOP;
      END IF;
    END LOOP;
  END IF;
END $$;

-- 기존 등록 함수는 이름을 바꿔 안쪽에서 부른다(같은 소유자라 SECURITY DEFINER 안에서 그대로 불린다). 기기 롤은 바깥 함수만 부른다.
ALTER FUNCTION lifelog.register_plugin(jsonb, text) RENAME TO register_plugin_core;
REVOKE EXECUTE ON FUNCTION lifelog.register_plugin_core(jsonb, text) FROM lifelog_devices;

CREATE FUNCTION lifelog.register_plugin(m jsonb, p_device text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, lifelog, public, extensions AS $$
DECLARE
  extra_keys text[] := ARRAY['platforms','requires'];
  stripped jsonb;
  existing jsonb;
  result jsonb;
BEGIN
  IF jsonb_typeof(m) <> 'object' THEN RAISE EXCEPTION '매니페스트는 객체여야 합니다'; END IF;
  PERFORM lifelog.validate_platforms_requires(m);
  stripped := m - extra_keys;
  -- 안쪽 함수는 두 키를 뺀 매니페스트로 "같은 버전·다른 내용"을 검사한다. 저장본이 두 키만 다르거나 같으면(재등록)
  -- 저장본을 잠깐 같은 모양(두 키를 뺀 것)으로 두고 부른 뒤, 끝나면 새 매니페스트 전체로 바꾼다(한 트랜잭션 안)
  SELECT manifest INTO existing FROM lifelog.plugin_versions WHERE plugin_id = m->>'id' AND version = m->>'version';
  IF existing IS NOT NULL AND (existing - extra_keys) = stripped THEN
    UPDATE lifelog.plugin_versions SET manifest = stripped WHERE plugin_id = m->>'id' AND version = m->>'version';
  END IF;
  result := lifelog.register_plugin_core(stripped, p_device);
  UPDATE lifelog.plugin_versions SET manifest = m WHERE plugin_id = m->>'id' AND version = m->>'version' AND manifest = stripped;
  RETURN result;
END $$;
REVOKE ALL ON FUNCTION lifelog.register_plugin(jsonb, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION lifelog.register_plugin(jsonb, text) TO lifelog_devices;
