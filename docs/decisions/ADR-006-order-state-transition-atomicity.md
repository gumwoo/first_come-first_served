# ADR-006 · 주문 상태 전이 원자화 — 조건부 UPDATE + 멱등키

- 상태: Accepted
- 날짜: 2026-07-07
- 슬라이스: S05(주문·결제)
- 관련: [[order-payment]], [[ADR-003]](재고 원자성), [[ADR-005]]

## 맥락
주문 상태(PENDING/PAID/VBANK_WAITING/EXPIRED…)는 **동시에 여러 경로**에서 바뀔 수 있다:
결제 승인, 웹훅(입금), 만료 sweep(@Scheduled). 여기에 사용자 더블클릭·PG 웹훅 재전송이 겹치면
**이중 PAID·이중 티켓·상태 꼬임**이 생긴다. "돈은 한 번만, 표도 한 번만"이 깨진다.

## 결정
1. **상태 전이는 조건부 UPDATE로 원자화** (재고 원자성 ADR-003과 같은 결):
   ```sql
   UPDATE orders SET status='PAID', paid_at=now()
   WHERE id=:id AND status='PENDING';
   ```
   영향 1행이면 이 요청이 전이의 주인, 0행이면 이미 다른 경로가 전이함(무시/기존결과 반환).
2. **결제 멱등성**: `payments.idempotency_key` UNIQUE로 "같은 결제 시도"의 중복 생성을 DB가 차단.
   재시도(사용자 의도)는 **새 키 = 새 payment row**로 허용.
3. **경합 규칙**: 승인(→PAID) vs 만료 sweep(→EXPIRED)이 동시에 와도 조건부 UPDATE로 **한쪽만** 성공.
   PAID가 이겼으면 sweep의 EXPIRED UPDATE는 0행(이미 PAID) → 그대로 종료.
4. 좌석/hold 반영도 전이 성공(1행) 시에만: seats HELD→SOLD, hold HELD→CONVERTED.
   (TS-007 교훈: `@Modifying(clearAutomatically)` 벌크 뒤 엔티티 mutate 금지 — 순서·saveAndFlush 주의.)

## 고려한 대안
- **낙관적 락(@Version)**: 가능하나 재시도 루프·충돌 예외 처리가 번거로움. 상태 전이는 조건부
  UPDATE가 더 단순·명확.
- **비관적 락(SELECT FOR UPDATE)**: 결제 구간 잠금 → 처리량 저하. 단일 행 상태 전이엔 과함.
- **애플리케이션 레벨 if(status==PENDING) then set**: check-then-act 레이스(IMP-003/007과 같은 함정) → 기각.

## 결과 / 한계
- 이중 PAID/이중 발급 = 0 (대표 IMP로 before/after 측정 예정).
- "이미 처리됨"을 에러가 아니라 **기존 결과 반환**으로 다루는 게 멱등의 핵심(2xx 유지).
- 분산 확장 시에도 DB 단일 행 조건부 UPDATE가 진실원이라 안전(상태는 DB에 있음).
