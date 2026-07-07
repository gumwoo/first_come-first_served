# TS-008 · 좌석 선점 시 seatIds의 이벤트 소속 미검증 — 교차 이벤트 선점(IDOR)

- 슬라이스: `S04`(좌석·재고)
- 날짜: 2026-07-07
- 유형: 정합성/보안 버그(코드)
- 발견 경위: S04 마감 전 코드 리뷰(읽기·정적 점검)
- 관련 커밋/PR: PR #71
- 관련 문서: [IMP-003](../improvements/IMP-003-oversell.md)(조건부 UPDATE), [ADR-004](../decisions/ADR-004-pricing-source.md)(가격 소스)

> 순서: 증상(가능성) → 조사 → 근본 원인 → 해결 → 재발 방지.

## 1. 증상(잠재 결함)
`POST /events/{id}/seats/hold`는 큐 토큰이 그 이벤트에 ADMITTED인지 검증한다. 그러나 실제
좌석 선점 UPDATE가 **body의 seatIds만 보고** 실행되고, 그 좌석들이 path의 `eventId` 소속인지
확인하지 않았다. 이론상 사용자가 **이벤트 A의 입장 토큰으로 이벤트 B의 좌석 id**를 넣으면
B 좌석이 HELD 될 수 있었다.

## 2. 조사
- `SeatService.hold()`: `isAdmitted(token, eventId)`로 토큰-이벤트만 확인 → 좌석 소속 검증 없음.
- `SeatRepository.holdIfAvailable()`의 조건부 UPDATE:
  `where s.id in :ids and s.status = AVAILABLE` — **eventId 조건 부재.**
- 파급:
  - `SeatHold`는 `eventId = A`로 저장되는데 items는 B 좌석 → **재고/홀드 정합성 붕괴.**
  - `totalPrice(eventId=A, seatIds=B)`가 **A의 가격표로 B 좌석 등급 가격을 계산** → 가격 오염
    (A·B 티어가 다르면 금액이 틀림, ADR-004의 "가격 단일 소스" 위반).
  - 좌석맵 SSE도 A 이벤트로만 나가 B 이벤트 구독자에 반영되지 않음.

## 3. 근본 원인
**인증/입장 게이트는 통과해도 "요청 리소스가 경로 리소스에 속하는지"를 검증하지 않은 전형적
IDOR성 결함.** 원자적 초과판매 방지(조건부 UPDATE)에만 집중해 좌석의 이벤트 소속을 불변식에서
빠뜨렸다.

## 4. 해결 (이중 방어)
1. **사전 소속 검사**: 요청 좌석 중 해당 이벤트 소속 개수가 요청 수와 다르면 거부.
   ```java
   if (seatRepository.countByIdInAndEventId(seatIds, eventId) != seatIds.size())
       throw new BusinessException(ErrorCode.VALIDATION_ERROR);
   ```
   → 다른 이벤트 좌석 혼입 시 명확한 400.
2. **원자 UPDATE에도 eventId 가드**(방어선 이중화):
   ```sql
   where s.id in :ids and s.eventId = :eventId and s.status = AVAILABLE
   ```
   → 사전 검사와 UPDATE 사이 경합에도 다른 이벤트 좌석은 절대 HELD 되지 않음.

## 5. 재발 방지
- 통합 테스트 추가: 이벤트 A 입장 토큰으로 이벤트 B 좌석 선점 시도 → 거부되고 B 좌석은
  `AVAILABLE` 유지(`다른_이벤트_좌석은_선점할_수_없다`).
- 교훈: **인증·게이트 통과 ≠ 리소스 소속 검증.** path의 상위 리소스와 body의 하위 리소스
  소속을 항상 대조한다(주문/결제 등 후속 슬라이스에도 동일 원칙 적용).

## 함께 정리한 것(같은 PR)
- **SSE 계약 갭 해소**: `seat.held`(선점)·`seat.hold.released`(해제) broadcast를 구현해
  `contracts/events.yaml` 3종을 모두 만족. 프론트(`useSeats`)도 세 이벤트를 구독해 좌석맵이
  타 사용자의 선점/해제/만료를 실시간 반영. (기존엔 `seat.hold.expired`만 발행)
- **문서 정합**: `docs/common/api-contract.md`가 `seat.hold.expired`를 `/sse/orders/:id`로 적던
  stale을 `/sse/events/:id/seats`로 바로잡고 3종 명시. `docs/rules/domain-rules.md`의 전송 채널에
  좌석 SSE 채널 추가.

## 한계 / 남은 것
- 선점/해제 broadcast는 만료 sweep과 동일하게 트랜잭션 내에서 발행(기존 패턴). 커밋 직전 push라
  구독자 재조회가 극히 짧게 앞설 수 있으나, 재조회는 커밋 후 상태를 읽어 수렴. 단일 서버 가정.
