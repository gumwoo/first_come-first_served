# IMP-009 · 환불 이중 처리 — refunds.idempotency_key UNIQUE + 조건부 상태전이

- 슬라이스: `S06`
- 날짜: 2026-07-15
- 유형: 정량(동시성 테스트로 측정) — 동시성·정합성
- 관련 커밋/PR: S06 BE-2
- 벤치 파일: [`benchmarks/refund-idempotency-before.json`](../../benchmarks/refund-idempotency-before.json)
  → [`benchmarks/refund-idempotency-after.json`](../../benchmarks/refund-idempotency-after.json)
- 관련: [[ADR-006]](주문 상태전이 원자화), [[IMP-008]](결제 멱등), [[TS-010]](벌크 UPDATE 컨텍스트 클리어)

> 흐름: 문제 정의 → 증상(수치) → 가설 → 도구로 검증 → 해결 → 재측정 → 한계.

## 1. 상황
환불도 결제만큼 "**돈은 한 번만 돌려주고, 좌석도 한 번만 재고로 되돌린다**"가 지켜져야 한다.
사용자는 "환불 신청" 버튼을 더블클릭할 수 있고, 클라이언트 재시도도 온다.

## 2. 문제 정의 + 분류
`SELECT status → PAID이면 UPDATE REFUNDED + INSERT refund + 좌석 복구`는 **check-then-act 레이스**다.
- 계층 분류: **동시성**(취소 요청도 Tomcat 요청 스레드 다수 → 단일 서버에서도 실재).
- 왜: 두 요청이 거의 동시에 `status=PAID`를 관측 → 각자 환불행을 만들고 좌석을 두 번 복구한다.
  → 이중 환불(돈)·이중 재고 복구(정합성 붕괴).

## 3. 증상 (측정된 증거)
- 측정: `RefundIdempotencyIntegrationTest` — 같은 PAID 주문에 10스레드 동시 환불(레이스 창 20ms).
- before(비원자):
  | 지표 | 값 |
  |------|----|
  | 동시 클릭 | 10 |
  | 환불행 | **8** |
  | 이중 환불(초과) | **7** |
  | 좌석 복구 | 8 |
  > 정확한 건수는 레이스 타이밍 의존(CI 실행값). 테스트는 `환불행>1`을 단언. IMP-008과 동일한
  > 10스레드/20ms 구조라 유사값(≈7).

## 4. 가설 (검증 전)
- 상태 전이가 원자적이지 않고, 환불 시도의 중복을 DB가 막지 않아 여러 번 환불된다.

## 5. 검증 (도구로 확인 → 확정 원인)
- 도구: 동시성 통합 테스트(Testcontainers). 비원자 check-then-act를 10스레드로 재현.
- 결과: 환불행이 1을 크게 초과(>1) → 이중 환불/이중 복구 확정.

## 6. 해결 + 재측정
1. **`refunds.idempotency_key` UNIQUE** — 같은 환불 시도(키)의 중복 생성을 DB가 원천 차단.
2. **주문 조건부 전이** — `UPDATE orders SET status='CANCELLED' WHERE id=? AND status='PAID'`.
   1행이면 이 요청이 취소의 주인, 0행이면 이미 취소/환불됨 → 거부. 이어서 `CANCELLED→REFUNDED`.
3. **충돌 시 기존 결과 반환** — 동시 같은 키로 UNIQUE 충돌이 난 패자는 예외 대신 기존 환불 결과를
   멱등 반환 → 더블클릭 둘 다 "성공"으로 수렴, 이중 효과 0.
4. (동반) 좌석 복구(벌크 UPDATE) 전에 refund를 `saveAndFlush`로 확정 — 컨텍스트 클리어 유실 방지([[TS-010]]).
- after:
  | 지표 | 값 |
  |------|----|
  | 동시 클릭 | 10 |
  | 환불행 | **1** |
  | 이중 환불 | **0** |
  | 좌석 복구 | 1회 |

**이중 환불 7 → 0.** (S04 초과판매 19→0, S05 이중결제 7→0에 이어 S06 이중환불 N→0 — 동일 원자화 패턴을 취소 경로에 재사용.)

## 7. 한계
- 조건부 UPDATE는 단일 행 상태라 분산 확장에도 DB가 진실원이라 안전.
- 실 PG 환불 재전송(웹훅형)까지의 멱등은 `pg_refund_tid` 대조로 확장 여지(현재 Mock/카드 취소 기준).
