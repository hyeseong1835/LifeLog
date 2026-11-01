-- 0006 플러그인 출처 URL. 설치 도구가 GitHub URL로 설치하면 plugins.source_url에 적는다.
-- 다른 기기의 설치 화면이 같은 출처를 보여 주고, 나중에 갱신 확인에도 쓴다. 기기 롤이면 누구나 적을 수 있다.

CREATE FUNCTION lifelog.set_plugin_source(p_id text, p_url text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, lifelog, public, extensions AS $$
BEGIN
  IF lifelog.current_device() IS NULL THEN RAISE EXCEPTION '기기 롤로만 부를 수 있습니다'; END IF;
  IF p_url IS NOT NULL AND p_url !~ '^https?://' THEN RAISE EXCEPTION 'URL이 아닙니다: %', p_url; END IF;
  UPDATE lifelog.plugins SET source_url = p_url WHERE id = p_id;
  IF NOT FOUND THEN RAISE EXCEPTION '등록되지 않은 플러그인: %', p_id; END IF;
END $$;
REVOKE ALL ON FUNCTION lifelog.set_plugin_source(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION lifelog.set_plugin_source(text, text) TO lifelog_devices;
