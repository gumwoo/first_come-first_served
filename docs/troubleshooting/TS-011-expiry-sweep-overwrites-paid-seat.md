# TS-011 · 만료 sweep이 결제 확정된 좌석을 되돌릴 수 있음 — 무가드 복구 UPDATE (형제 경로 재발)

- 슬라이스: `S04`(좌석·재고) / `S05`(결제 경합)
- 날짜: 2026-07-29
- 유형: 정합성 버그(코드) — check-then-act 레이스
- 관련 커밋/PR: PR `fix/seat-expiry-race`
- 관련 문서: [[ADR-003]](재고 원자성), [[ADR-006]](상태전이 원자화), [[TS-007]]·[[TS-008]](형제 경로 패턴)

> 순서: 증상 → 조사 → 근본 원인 → 해결 → 재발 방지.

## 1. 증상
K8s 다중 Pod 배포 검토 중 만료 스케줄러(`SeatHoldExpiryService.sweepExpired`)를 점검하다 발견.
**매번 나는 버그는 아니지만 재현 가능한 동시성 취약점**: 결제 확정과 만료 sweep이 경합하면
이미 **결제된(SOLD) 좌석이 AVAILABLE로 되돌아가 재판매(초과판매)** 될 수 있다. 단일 Pod에서도
@Scheduled 스레드와 결제 요청 스레드가 겹치면 발생 — 쿠버네티스 때문에 새로 생기는 문제가 아니다.

## 2. 조사
sweep의 흐름은 **조회 후 무조건 갱신**(check-then-act):
```java
List<SeatHold> holds = holdRepository.findByStatusAndExpiresAtBefore(HELD, now); // SELECT (HELD·만료)
... // hold별 seatId 수집
seatRepository.releaseSeats(allSeatIds, AVAILABLE); // 무가드 UPDATE
holdRepository.expireHolds(holdIds);                // 무가드 UPDATE
```
그런데 복구 쿼리에 **상태 가드가 없었다**:
```sql
update Seat s set s.status = :available where s.id in :ids          -- status 확인 없음
update SeatHold h set h.status = EXPIRED where h.id in :ids         -- status 확인 없음
```
반면 결제(전이) 경로는 **조건부 가드가 있다**(프로젝트 논지, ADR-003/006):
```sql
sellSeats:   ... where s.id in :ids and s.status = :held   -- HELD만 SOLD
convertHold: ... where h.id = :id and h.status = HELD      -- HELD만 CONVERTED
```

## 3. 근본 원인
SELECT와 UPDATE 사이의 창에서 결제가 이기면:
```
t0  sweep: SELECT → 이 홀드를 HELD·만료로 획득
t1  결제: sellSeats(HELD→SOLD), convertHold(HELD→CONVERTED)  (가드 통과, 커밋)
t2  sweep: releaseSeats(무가드) → SOLD→AVAILABLE 덮어씀 / expireHolds(무가드) → CONVERTED→EXPIRED
```
결제 시작 시 `expiresAt` 만료 검사를 해도 이 레이스는 사라지지 않는다 — **검사 후 PG 승인·DB 전이
사이에 시간이 있기** 때문. 즉 **sell/convert에는 조건부 가드를 줬으면서 형제 경로인 release/expire에는
빠뜨린 것**이 원인. TS-007/008과 같은 "같은 원칙이 형제 경로에 미적용" 패턴의 재발.

## 4. 해결
복구 쿼리도 **조건부 가드**로 만든다. `releaseSeats`는 용도별 `from` 상태가 다르므로(만료·수동해제=HELD,
환불=SOLD) `from`을 파라미터화:
```sql
releaseSeats: ... where s.id in :ids and s.status = :from     -- 만료/해제 from=HELD, 환불 from=SOLD
expireHolds:  ... where h.id in :ids and h.status = HELD      -- 여전히 HELD인 홀드만
```
호출부: 만료 sweep·수동 해제 `from=HELD`, 환불 `from=SOLD`. 이제 결제가 먼저 이기면 좌석이 SOLD라
`releaseSeats(from=HELD)`는 0행 → **덮어쓰지 않음**. 정합성은 **락이 아니라 원자 조건부 연산**이
보장한다는 프로젝트 원칙(ADR-002/006)을 만료 경로에도 일관 적용.

## 5. 재발 방지
- 통합테스트 `결제와_만료sweep_경합시_SOLD좌석은_복구되지_않는다`: 결제가 이긴 뒤 sweep 복구 쿼리가
  0행이고 좌석 SOLD·홀드 CONVERTED가 유지되는지 검증(Testcontainers, 결정적).
- 교훈(3번째 재확인): **한 도메인에서 조건부 가드를 도입하면, 같은 자원을 건드리는 형제 경로 전부에
  같은 가드가 있는지 훑는다.** sell↔release, convert↔expire는 짝이다.
- **정적 가드(구현 완료)**: 하네스 `harness/backend/check.mjs`에 규칙 추가 — `@Query` UPDATE가 status를
  set하면서 WHERE에 status 조건(`=`/`in`)이 없으면 실패. 의도적 무가드는 `harness:allow-unguarded-status`
  주석으로만 예외. 메타테스트 fixture `be-unguarded-status-update`로 하네스 자체도 검증. 이 규칙이
  release/expire·sellSeats 같은 형제 경로 누락을 앞으로 CI에서 자동 차단한다.

## 6. 반대 방향 — 만료 sweep이 먼저 이기면 결제가 주문만 PAID로 (추가 발견)
§1~5는 "결제가 먼저 이긴 뒤 sweep이 덮어쓰는" 방향을 닫았다. 그런데 **반대 순서도 위험**했다:
```
t0  만료 sweep 승리: 좌석 HELD→AVAILABLE, 홀드 HELD→EXPIRED (order는 아직 PENDING — order-sweep 미실행)
t1  결제 finalizePaid: markPaid(PENDING→PAID) 성공
    → sellSeats(HELD→SOLD) 0행, convertHold(HELD→CONVERTED) 0행 — 그러나 반환값 미검사
    → 커밋 → 주문 PAID인데 좌석 AVAILABLE·홀드 EXPIRED (결제했는데 좌석 재판매 가능, 더 나쁨)
```
결제 진입 시 `expiresAt` 검사를 통과해도(승인 중 시간 경과) 발생하며, order-sweep과 seat-hold-sweep이
별도 스케줄러라 "홀드는 풀렸는데 주문은 아직 PENDING"인 창이 존재한다.

**근본 원인**: `finalizePaid`가 `markPaid`만 검사하고 `sellSeats`/`convertHold`의 **영향 행 수를 버렸다** —
sweep 쪽엔 넣은 "영향 행 수 검증"(ADR-003)을 결제 확정 쪽에 안 넣은 형제 경로 누락(TS-011의 대칭).

**해결**: 영향 행 수 불일치 시 예외 → 트랜잭션 전체 롤백(markPaid·approve 포함).
```java
int sold = seatRepository.sellSeats(seatIds, SOLD, HELD);
int converted = holdRepository.convertHold(order.getHoldId());
if (sold != seatIds.size() || converted != 1) throw new BusinessException(INVALID_STATE_TRANSITION);
```
좌석이 이미 풀렸으면 sold=0 → 예외 → **주문이 PAID로 확정되지 않음**(PENDING 유지, 재시도/만료로 흡수).
검증: `만료sweep이_먼저_이기면_결제는_실패하고_주문은_PAID로_남지_않는다`(PaymentIntegrationTest).

## 7. 잔여 개선 반영 (④ 정확한 알림 · ③ PG 보상)
데이터 정합성(§forward 가드 + §6 영향행수 검증)에 더해, 아래 두 개의 "경계 위생" 항목을 후속으로 닫았다.

- **④ 정확한 만료 알림** — 이전엔 sweep이 복구 대상으로 미리 잡은 seatId 전부에 `seat.hold.expired`를
  브로드캐스트해, 결제가 이겨 실제로 안 풀린 SOLD 좌석에도 유령 알림이 나갔다(정합성엔 무해하나 FE가
  매진 좌석을 잠깐 풀린 것처럼 표시하는 오탐). `SeatHoldExpiryService.sweepExpired`를 **홀드별로 조건부
  EXPIRED 전이(`expireHolds` 1행) → 성공한 홀드의 좌석만 해제·알림**하도록 재구조화. CONVERTED(결제 승리)면
  0행 → 스킵. 검증: `결제가_이긴_홀드는_sweep이_만료알림을_보내지_않는다`(SeatInventoryIntegrationTest,
  `@SpyBean` 브로드캐스트 never).
- **③ PG 승인 보상(void)** — 반대 방향(§6)에서 PG 승인은 났는데 좌석이 만료 sweep에 풀려 확정 불가일 때,
  DB만 롤백하면 실 PG(Toss)엔 미아 승인이 남아 돈이 잡힌다. `finalizePaid`의 영향행수 불일치 경로에서
  **`gateway.refund(pgTid, amount)`로 승인을 취소(void)한 뒤 예외를 던져** 롤백한다(Mock은 no-op 성공,
  Toss는 결제취소 API). 검증: `만료로_확정실패시_이미난_PG승인을_보상취소한다`(PaymentIntegrationTest,
  `@SpyBean` refund 1회).

## 한계 / 남은 것
- ③ 보상은 **finalizePaid 내 best-effort void**다. "PG 승인 성공 직후·refund 호출 전 프로세스 크래시"
  구간까지 원자적으로 닫으려면 **outbox/saga(승인 결과를 커밋 후 재시도 가능한 이벤트로 기록)**가 필요 —
  실 결제 트래픽·모니터링과 함께 설계할 후속(S08+). 가상계좌(vbank) 경로의 보상 취소 의미(입금 반환)도
  실 PG 배선 시 별도 검토.
- 다중 Pod의 스케줄러 중복 실행(ShedLock)·SSE 팬아웃(Redis pub/sub)은 별개 항목(배포 문서 S08 참조),
  본 버그와 무관하게 정합성은 이 가드로 이미 안전.
