-- 좌석 + 등급별 가격 (KOPIS엔 없어 시딩으로 생성)
CREATE TABLE seats (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id   BIGINT       NOT NULL REFERENCES events(id),
    grade      VARCHAR(10)  NOT NULL,
    zone       VARCHAR(20),
    seat_row   VARCHAR(10),
    seat_col   INTEGER,
    status     VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX ix_seats_event_status ON seats (event_id, status);
ALTER TABLE seats ADD CONSTRAINT uq_seats_pos UNIQUE (event_id, zone, seat_row, seat_col);
COMMENT ON TABLE seats IS '개별 좌석. 재고·상태 진실원. 선점은 조건부 UPDATE로 원자화(ADR-003)';

CREATE TABLE event_seat_prices (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id   BIGINT      NOT NULL REFERENCES events(id),
    grade      VARCHAR(10) NOT NULL,
    price      INTEGER     NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT now()
);
ALTER TABLE event_seat_prices ADD CONSTRAINT uq_event_grade_price UNIQUE (event_id, grade);
COMMENT ON TABLE event_seat_prices IS '이벤트x등급 절대 가격. 결제 가격 단일 진실원(ADR-004)';
