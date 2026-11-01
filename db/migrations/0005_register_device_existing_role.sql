-- 0005 register_device: 롤은 클러스터 전체 것이라 DB를 새로 만들거나 덤프를 복원하면 기기 행 없이 롤만 남는다.
-- 그 경우 거부하지 않고 롤 비밀번호를 새로 정해 재사용한다. 기기 행이 살아 있으면 여전히 거부한다.

CREATE OR REPLACE FUNCTION lifelog.register_device(p_id text, p_name text, p_platform text) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, lifelog, public, extensions AS $$
DECLARE
  role_name text := 'lifelog_device_' || p_id;
  pw text := encode(gen_random_bytes(24), 'base64');
BEGIN
  IF p_id !~ '^[a-z][a-z0-9-]{1,40}$' THEN
    RAISE EXCEPTION '기기 id는 ^[a-z][a-z0-9-]{1,40}$ 꼴이어야 합니다: %', p_id;
  END IF;
  IF EXISTS (SELECT 1 FROM lifelog.devices WHERE id = p_id AND revoked_at IS NULL) THEN
    RAISE EXCEPTION '이미 등록된 기기입니다: %. 비밀번호를 다시 받으려면 reset_device_password를 쓰세요', p_id;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
    EXECUTE format('ALTER ROLE %I LOGIN PASSWORD %L', role_name, pw);
    EXECUTE format('GRANT lifelog_devices TO %I', role_name);
  ELSE
    EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L IN ROLE lifelog_devices', role_name, pw);
  END IF;
  INSERT INTO lifelog.devices (id, name, platform) VALUES (p_id, p_name, p_platform)
    ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, platform = EXCLUDED.platform, revoked_at = NULL, first_seen = now();
  RETURN pw;
END $$;
REVOKE ALL ON FUNCTION lifelog.register_device(text, text, text) FROM PUBLIC;
