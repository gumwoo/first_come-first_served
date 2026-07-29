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

## 한계 / 남은 것
- 최소 수정(가드 추가)으로 **데이터 정합성**은 닫았다. 다만 sweep이 복구 대상으로 미리 잡은 seatId 중
  결제가 이긴 좌석은 실제로 안 풀렸는데도 `seat.hold.expired` SSE를 브로드캐스트한다(전달상 경미한
  과알림, best-effort라 무해). 완전 정확히 하려면 "실제로 전이 성공한 홀드의 좌석만 알림"(RETURNING
  기반)으로 후속 개선 여지 — 이번 PR 범위 밖.
- 다중 Pod의 스케줄러 중복 실행(ShedLock)·SSE 팬아웃은 별개 항목(배포 문서 참조), 본 버그와 무관하게
  정합성은 이 가드로 이미 안전.
- **반대 방향(§6)의 PG 보상**: 실 PG(Toss)가 이미 승인한 뒤 DB를 롤백하면 승인만 남아 돈이 잡힐 수 있다.
  Mock/데모 게이트웨이 범위에선 DB 상태전이 검증으로 충분하나, 실 운영에선 **승인 취소(void)·보상 트랜잭션**을
  함께 설계해야 한다 — 라이브 결제 배선 시 후속.
