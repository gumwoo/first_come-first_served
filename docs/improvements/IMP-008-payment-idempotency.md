# IMP-008 · 결제 이중 처리 — idempotency_key UNIQUE + 조건부 상태전이

- 슬라이스: `S05`
- 날짜: 2026-07-10
- 유형: 정량(동시성 테스트로 측정) — 동시성·정합성
- 관련 커밋/PR: S05 BE-4
- 벤치 파일: [`benchmarks/payment-idempotency-before.json`](../../benchmarks/payment-idempotency-before.json)
  → [`benchmarks/payment-idempotency-after.json`](../../benchmarks/payment-idempotency-after.json)
- 관련: [[ADR-006]](주문 상태전이 원자화), [[IMP-003]](초과판매), [[IMP-007]](1인1토큰)

> 흐름: 문제 정의 → 증상(수치) → 가설 → 도구로 검증 → 해결 → 재측정 → 한계.

## 1. 상황
결제 확정은 "**돈은 한 번만 받고, 표(QR)도 한 번만 발급**"해야 한다. 그런데 사용자는 결제
버튼을 더블클릭할 수 있고, PG 웹훅은 같은 주문에 여러 번 재전송될 수 있다.

## 2. 문제 정의 + 분류
`SELECT status → PENDING이면 UPDATE PAID + INSERT payment`는 **check-then-act 레이스**다.
- 계층 분류: **동시성**(결제는 Tomcat 요청 스레드 다수 + 웹훅 재전송 → 단일 서버에서도 실재).
- 왜: 두 요청이 거의 동시에 `status=PENDING`을 관측 → 각자 PAID로 확정하고 결제행을 만든다.

## 3. 증상 (측정된 증거)
- 측정: `PaymentIdempotencyIntegrationTest` — 같은 주문에 10스레드 동시 결제(레이스 창 20ms).
- before:
  | 지표 | 값 |
  |------|----|
  | 동시 클릭 | 10 |
  | PAID 전이 | **8** |
  | 이중 PAID(초과) | **7** |
  | 결제행 | 8 |

## 4. 가설 (검증 전)
- 상태 전이가 원자적이지 않고, 결제 시도의 중복을 DB가 막지 않아 여러 번 확정된다.

## 5. 검증 (도구로 확인 → 확정 원인)
- 도구: 동시성 통합 테스트(Testcontainers). 비원자 check-then-act를 10스레드로 재현.
- 결과: PAID 전이가 1을 크게 초과(>1) → 이중 결제/이중 발급 확정.

## 6. 해결 + 재측정
1. **`payments.idempotency_key` UNIQUE** — 같은 결제 시도(키)의 중복 생성을 DB가 원천 차단.
2. **주문 상태 조건부 UPDATE** — `UPDATE orders SET status='PAID' WHERE id=? AND status='PENDING'`.
   1행이면 이 요청이 전이의 주인, 0행이면 이미 전이(만료/타 결제)됨.
3. **충돌 시 기존 결과 반환** — 동시 같은 키로 UNIQUE 충돌이 난 패자는 예외 대신 기존 결제 결과를
   멱등 반환(2xx 유지) → 더블클릭 둘 다 "성공"으로 수렴, 이중 효과 0.
- after:
  | 지표 | 값 |
  |------|----|
  | 동시 클릭 | 10 |
  | PAID 전이 | **1** |
  | 이중 PAID | **0** |
  | 결제행 | 1 · 좌석 1회 SOLD |

**이중 PAID 7 → 0.** (S03 정원초과 7→0, S04 초과판매 N→0에 이어 S05 이중결제 N→0)

## 7. 한계
- 웹훅 재전송 멱등은 `pg_tid` 기준 대조로 확장 예정(BE-5, 실 PG 어댑터).
- 조건부 UPDATE는 단일 행 상태이므로 분산 확장에도 DB가 진실원이라 안전.
