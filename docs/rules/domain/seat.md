# Domain · S04 좌석 / 재고 / 가격

선착순의 정확성이 가장 첨예한 슬라이스. 재고·가격·선점은 **우리 DB가 진실원**(KOPIS엔 없음).

## 재고 / 좌석
- 좌석 상태: `AVAILABLE → HELD → SOLD`. 해제/만료 시 `HELD → AVAILABLE`로 복구. [T]
- **좌석 선점은 원자적** — `UPDATE seats SET status='HELD' WHERE id IN(:ids) AND status='AVAILABLE'`
  후 **영향 행 수 == 요청 수** 검증. 아니면 롤백 + `SOLD_OUT`. check-then-act 금지(초과판매). [T] ([ADR-003], [[IMP-003-oversell]])
- **재고 정합성 불변식**: 총좌석 = AVAILABLE + HELD + SOLD (횡단, domain-rules.md). [T]
- 매진(잔여 0)에서 신규 선점 금지 → `SOLD_OUT`. [T]
- 부분 선점 실패(요청 3석 중 1석 매진) → **전부-or-전무**(전체 롤백 + SOLD_OUT). [T]

## 선점(HOLD) / 상태전이
- HOLD 상태(`SeatHoldStatus`): `HELD → RELEASED`(사용자 해제) / `HELD → EXPIRED`(TTL 만료) / `HELD → CONVERTED`(결제 확정, S05).
- 선점 TTL: `seat.hold-ttl`(약 5분). 만료 sweep(`@Scheduled`)이 `expires_at<now` 홀드를
  `EXPIRED` 처리 + 좌석 `AVAILABLE` 복구 + `seat.hold.expired` 발행. [T]
- 잘못된 상태전이 → `INVALID_STATE_TRANSITION`.

## 게이트 / 제한
- **입장 검증**: 선점은 S03 큐 토큰이 `ADMITTED`(그 이벤트)일 때만. 아니면 `QUEUE_NOT_ADMITTED`. [T]
- **1인 구매 한도**: 이벤트당 1인 최대 수량 초과 → `MAX_PER_USER_EXCEEDED`. [T]
- 이미 보유한 좌석 재선점 → `DUPLICATE_BOOKING`.

## 좌석·가격 시딩 (KOPIS엔 없음 → 우리가 생성)
- KOPIS 동기화로 새 공연이 저장되면, sellable(ON_SALE/SCHEDULED) 공연에 **기본 좌석맵을 자동 생성**.
  기본 템플릿: VIP 10 / R 20 / S 30 / A 40 (100석).
- **멱등**: 이미 좌석이 있으면 skip(빠른 경로) + `unique(event_id, zone, seat_row, seat_col)`·
  `unique(event_id, grade)`가 최종 방어선(재실행·동시 시딩 중복 차단). [T]
- **관리자 트리거**: `POST /admin/events/{id}/seats`는 누락/재시드용 수동 보조(있으면 skip). 필수 경로 아님.
- best-effort: 한 공연 시딩 실패가 동기화 전체를 막지 않는다.
- `GET /events/{id}/seats`는 **생성하지 않는다**(순수 조회).

## 가격 정책 (단일 진실원)
- **결제 가격의 유일 진실원 = `event_seat_prices`**(이벤트×등급 절대 가격). [R] ([ADR-004])
- **KOPIS `priceText`는 결제에 미사용** — 자유텍스트라 신뢰 불가(무료/미정/등급텍스트/범위).
  상세의 "원 공연 안내" 블록에 원문만 표시(무료/미정은 숨김). [R]
- 가격 생성: **장르 기반 티어 + eventId 기반 fallback** (priceText 파싱 안 함). 등급 배수 고정
  (A=1.0, S=1.3, R=1.6, VIP=2.0). 시딩 시 **절대값 계산·저장**, 같은 eventId는 항상 같은 값(멱등). [T]
  - 장르 매핑(예): 대중음악/뮤지컬→HIGH, 클래식/무용→MID, 연극/국악→LOW, 미매핑→`eventId % N`.
  - 무료 공연: 데모상 우리 티어(유료)로 처리(문서 명시), priceText 의존 회피.
- **base_price = 등급 최저가(A)** — 시딩 단계에서 `events.base_price`에 write → 목록 "가격 미정" 해소. [T]
- 표시 통일: 목록(최저가~), 상세(범위 + 원 공연 안내), 좌석선택(등급별), 결제(선택 합산) 모두 `event_seat_prices` 기준.

## 실시간
- `seat.held` / `seat.hold.released` / `seat.hold.expired` 발행. **`seat.hold.expired`는
  required_fe_subscribes** → 좌석맵/타이머가 SSE로 반영(하네스). 폴링 폴백.
- 채널: `GET /sse/events/{id}/seats`(이벤트별 다중 구독). 만료 sweep(`@Scheduled`)이 좌석을
  AVAILABLE로 복구하고 그 이벤트 구독자에 `seat.hold.expired`(freed seatIds) push. 단일 서버 가정.

## 테스트 계획 ([T] 요약)
- **oversell(IMP-003)**: 재고 1석 N동시 선점 → 성공 1·초과 0(조건부 UPDATE). naive 재현(before).
- 재고 정합성 불변식(총좌석=A+H+S), 부분실패 전부-or-전무.
- HOLD TTL 만료 → 재고 복구, 상태전이 위반 차단.
- QUEUE_NOT_ADMITTED 게이트, 구매 한도(MAX_PER_USER/DUPLICATE).
- 시딩 멱등(재시드·동시), 가격 티어 stable, base_price=최저가, priceText 분류(순수함수).

## 범위 밖 (S05)
- 결제·주문 확정(HOLD→CONVERTED, seat→SOLD), 티켓(QR), 결제 멱등.
