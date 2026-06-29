-- 지역(KOPIS area, 시도 단위) 추가 — 지역 필터용. KOPIS 목록 응답의 area 필드를 저장.
ALTER TABLE events ADD COLUMN region VARCHAR(50);
CREATE INDEX ix_events_region ON events (region);

COMMENT ON COLUMN events.region IS 'KOPIS area(시도, 예: 서울특별시). 지역 필터';
