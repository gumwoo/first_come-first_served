CREATE TABLE events (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kopis_id      VARCHAR(50),
    title         VARCHAR(200) NOT NULL,
    venue         VARCHAR(200),
    genre         VARCHAR(50),
    poster_url    VARCHAR(500),
    start_date    DATE,
    end_date      DATE,
    running_time  VARCHAR(50),
    age_limit     VARCHAR(50),
    status        VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED',
    base_price    INTEGER,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

-- KOPIS 동기화 식별자(있는 경우 유일). Postgres는 NULL 다중 허용 → 시드/수동 등록은 NULL 가능.
ALTER TABLE events ADD CONSTRAINT uq_events_kopis_id UNIQUE (kopis_id);
-- 목록/필터(상태+시작일) 조회 가속
CREATE INDEX ix_events_status_start ON events (status, start_date);
CREATE INDEX ix_events_genre ON events (genre);

COMMENT ON TABLE  events            IS '공연 이벤트(KOPIS 메타 + 거래용 필드는 S04에서 확장)';
COMMENT ON COLUMN events.kopis_id   IS 'KOPIS 공연ID. 동기화 upsert 식별자. 시드/수동은 NULL';
COMMENT ON COLUMN events.status     IS 'EventStatus: DRAFT/SCHEDULED/ON_SALE/PAUSED/SOLD_OUT/CLOSED';
COMMENT ON COLUMN events.base_price IS '표시용 최소 가격(원). 등급별 가격/재고는 S04 좌석에서 관리';
