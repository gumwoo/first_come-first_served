# IMP-010 · 좌석 만료 ↔ 결제 양방향 레이스 — 조건부 가드 + 영향행수 검증

- 슬라이스: `S04`(좌석·재고) / `S05`(결제 경합)
- 날짜: 2026-07-29
- 유형: 정량(동시성 테스트로 측정) — 동시성·정합성
- 관련 커밋/PR: PR #125(정방향 가드)·#126(반대방향 영향행수 검증)
- 벤치 파일: [`benchmarks/seat-payment-race-before.json`](../../benchmarks/seat-payment-race-before.json)
  → [`benchmarks/seat-payment-race-after.json`](../../benchmarks/seat-payment-race-after.json)
- 관련: [[TS-011]](사건 회고), [[ADR-003]](재고 원자성), [[ADR-006]](상태전이 원자화), [[IMP-003]]·[[IMP-008]]

> 흐름: 문제 정의 → 증상(수치) → 가설 → 도구로 검증 → 해결 → 재측정 → 한계.

## 1. 상황
결제 확정과 만료 sweep(@Scheduled)은 **같은 좌석·홀드**를 동시에 건드린다. "결제된 좌석은 다시 팔리면
안 되고, 결제 완료 주문은 반드시 좌석을 가져야 한다"가 불변식. 두 작업의 실행 순서에 따라 이 불변식이
양방향으로 깨질 수 있었다.

## 2. 문제 정의 + 분류
- 계층 분류: **동시성**(결제 = Tomcat 요청 스레드, sweep = 스케줄러 스레드 → 단일 서버에서도 실재).
- **정방향**(결제 승리 후 sweep): sweep의 `releaseSeats`/`expireHolds`에 status 가드가 없어, 이미 SOLD/
  CONVERTED된 행을 무조건 AVAILABLE/EXPIRED로 덮어씀 → 결제 좌석 재판매.
- **반대방향**(sweep 승리 후 결제): `finalizePaid`가 `markPaid`만 검사하고 `sellSeats`/`convertHold`의
  영향 행 수를 버려, 좌석이 이미 풀렸는데도 주문만 PAID로 확정 → 주문 PAID·좌석 AVAILABLE.

## 3. 증상 (측정된 증거)
- 측정: `PaymentIntegrationTest.IMP010_결제_만료_양방향_레이스_before_after` — 결제×sweep 경쟁을 10회
  (정방향/반대방향 각 1) 재현해 불일치 수 카운트(결정적 인터리빙).
- before:
  | 지표 | 값 |
  |------|----|
  | 시행(trials) | 10 |
  | 정방향: 결제 좌석 유실(SOLD→AVAILABLE) | **10** |
  | 반대방향: 주문 PAID·좌석 AVAILABLE | **10** |
  | 총 불일치 | **20** |

## 4. 가설 (검증 전)
- 상태 전이가 형제 경로(release/expire, sellSeats 카운트)에서 조건부·영향행수 검증이 빠져, 순서에 따라
  한쪽이 다른 쪽의 확정 결과를 덮어쓰거나 무시한다.

## 5. 검증 (도구로 확인 → 확정 원인)
- 도구: 동시성 통합 테스트(Testcontainers). naive(무가드 sweep + 영향행수 미검사 결제)로 양방향 재현.
- 결과: 20/20 불일치 → 두 방향 모두 확정 원인 확인(TS-011에 사건 상세).

## 6. 해결 + 재측정
1. **정방향** — `releaseSeats`에 `from` 상태 가드(만료·해제 `from=HELD`, 환불 `from=SOLD`),
   `expireHolds`에 `status=HELD` 가드. 결제가 이겨 SOLD면 0행 → 덮어쓰지 않음.
2. **반대방향** — `finalizePaid`에 **영향 행 수 검증**: `sold != seatIds.size() || converted != 1`이면
   예외 → 트랜잭션 전체 롤백(markPaid·approve 포함) → 주문이 PAID로 확정되지 않음.
- after:
  | 지표 | 값 |
  |------|----|
  | 시행 | 10 |
  | 정방향 좌석 유실 | **0** |
  | 반대방향 PAID·좌석없음 | **0** |
  | 총 불일치 | **0** |

**양방향 레이스 불일치 20 → 0.** 정합성은 락이 아니라 **원자 조건부 연산 + 영향 행 수 검증**이 보장한다는
프로젝트 원칙(ADR-002/003/006)을 sweep·결제 확정 양쪽에 일관 적용.

## 7. 한계
- 실 PG가 이미 승인한 뒤 DB 롤백 시 승인만 남을 수 있어, 실 운영은 승인 취소(void)·보상 트랜잭션 필요
  (Mock/데모 범위 밖, TS-011 §한계).
- 측정은 결정적 인터리빙(구성)으로 카운트한다 — OS 스케줄링 난수가 아니라 레이스 순서를 명시 재현해
  결과가 결정적(before 20, after 0). 형제 경로 누락 재발은 하네스 정적 규칙으로 별도 차단(TS-011 재발방지).
