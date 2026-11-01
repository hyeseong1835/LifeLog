-- 0003 기기 비밀번호 재발급. 관리자만. 기기 id·데이터는 그대로 두고 롤 비밀번호만 새로 만들어 한 번 돌려준다.
-- 쓰임: 이관 도구가 먼저 등록한 기기(phone)를 나중에 실제 앱이 쓸 때, 접속 정보를 잃었을 때.

CREATE FUNCTION lifelog.reset_device_password(p_id text) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  role_name text := 'lifelog_device_' || p_id;
  pw text := encode(gen_random_bytes(24), 'base64');
BEGIN
  IF NOT EXISTS (SELECT 1 FROM lifelog.devices WHERE id = p_id AND revoked_at IS NULL) THEN
    RAISE EXCEPTION '등록되지 않았거나 폐기된 기기입니다: %', p_id;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
    RAISE EXCEPTION '롤 %가 없습니다', role_name;
  END IF;
  EXECUTE format('ALTER ROLE %I PASSWORD %L', role_name, pw);
  RETURN pw;
END $$;
REVOKE ALL ON FUNCTION lifelog.reset_device_password(text) FROM PUBLIC;
