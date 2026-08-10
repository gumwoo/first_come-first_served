-- KOPIS 공연 상세 필드를 events에 보관한다.
--
-- 왜: 지금까지 이 값들은 GET /events/{id} 요청마다 KOPIS 상세 API를 동기 호출해 가져왔다.
-- 그러면 외부 호출량이 우리 트래픽의 함수가 된다 — KOPIS 이용 제한(IP당 1초 10회)을
-- 부하 시 약 7배 초과해 400 Request Blocked를 2,014건 맞았다. 사용자 응답시간도 외부 지연에
-- 직접 종속된다(p50 89ms vs 다른 조회 18ms).
--
-- 동기화 배치가 미리 채워두고 사용자 요청은 DB만 읽게 한다. 그러면 외부 호출량이 트래픽과
-- 분리되고(하루 1회 고정), KOPIS가 죽어도 이미 받아둔 값으로 화면이 유지된다.
--
-- 무중단 배포: **추가만 하는 DDL(nullable)이라 Expand-Contract의 N단계**다. 이 컬럼을 모르는
-- 구버전 Pod도 그대로 동작한다(파괴적 DDL 아님 — 승인 불필요).
--
-- ⚠️ 컬럼명 주의: KOPIS 필드명은 cast지만 CAST는 SQL 예약어라 매번 인용부호가 필요하다.
-- cast_info로 둔다.
ALTER TABLE events ADD COLUMN price_text    TEXT;
ALTER TABLE events ADD COLUMN cast_info     TEXT;
ALTER TABLE events ADD COLUMN synopsis      TEXT;
ALTER TABLE events ADD COLUMN schedule_text TEXT;
-- 상세를 언제 받아왔는지. NULL이면 아직 못 받은 것 → 동기화가 다음 차례에 다시 시도한다.
-- 매번 1,446건을 전량 재호출하지 않기 위한 기준이기도 하다.
ALTER TABLE events ADD COLUMN detail_synced_at TIMESTAMP;

COMMENT ON COLUMN events.price_text       IS 'KOPIS pcseguidance — 가격 안내 원문("전석 30,000원")';
COMMENT ON COLUMN events.cast_info        IS 'KOPIS prfcast — 출연진. 컬럼명이 cast가 아닌 이유는 SQL 예약어';
COMMENT ON COLUMN events.synopsis         IS 'KOPIS sty — 줄거리';
COMMENT ON COLUMN events.schedule_text    IS 'KOPIS dtguidance — 공연시간 안내 원문';
COMMENT ON COLUMN events.detail_synced_at IS '상세 동기화 시각. NULL이면 미수집 → 다음 동기화 대상';
