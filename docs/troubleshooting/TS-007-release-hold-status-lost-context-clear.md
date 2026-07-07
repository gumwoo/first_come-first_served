# TS-007 · 선점 해제 시 좌석만 풀리고 홀드는 HELD로 남음 — 벌크 UPDATE 컨텍스트 클리어

- 슬라이스: `S04`(좌석·재고)
- 날짜: 2026-07-06
- 유형: 정합성 버그(코드) — JPA 영속성 컨텍스트
- 관련 커밋/PR: PR #69
- 관련 문서: [IMP-003](../improvements/IMP-003-oversell.md)(조건부 UPDATE), [TS-006](TS-006-seat-hold-error-not-surfaced.md)

> 순서: 증상 → 조사 → 근본 원인 → 해결 → 재발 방지.

## 1. 증상
좌석 하나를 선점하고 "선점 취소"를 눌러도 화면이 안 바뀌고, 공연 상세로 나갔다 좌석 화면으로
다시 오면 **좌석맵엔 2석만 잠긴 것으로 보이는데 실제로는 4석이 잠긴 것처럼** 동작(1석만 더
골라도 `MAX_PER_USER_EXCEEDED`). 잔여 수와 1인 한도 계산이 어긋남.

## 2. 조사
event 2185, user 1의 실제 DB를 덤프:

| hold | hold_status | seat | seat_status |
|------|-------------|------|-------------|
| 6 | HELD | S17 | **AVAILABLE** |
| 7 | HELD | S8 | HELD |
| 8 | HELD | **S9** | HELD |
| 9 | HELD | **S9(중복)** | HELD |

- 홀드 6: 좌석은 풀렸는데(AVAILABLE) 홀드는 HELD로 남음.
- 홀드 8·9: 같은 좌석 S9를 둘 다 HELD로 소유(좌석이 풀린 사이 재선점돼 중복 생성).
- 1인 한도는 "HELD 홀드들의 아이템 수"로 계산(`SeatService.hold`) → 4로 세지만 실제 잠긴 좌석은 2.

## 3. 근본 원인
`SeatService.release()`가 `releaseSeats()` **뒤에** `hold.release()`(엔티티 mutate)를 호출.
`releaseSeats`는 `@Modifying(clearAutomatically = true)`(flushAutomatically 없음)라, 실행 시
**영속성 컨텍스트를 비운다.** 그 순간 이미 로드해둔 `hold`가 **detached** 되고, 이후
`hold.release()`(상태 → RELEASED)는 추적되지 않아 **DB에 반영되지 않는다.**

결과: 좌석 UPDATE는 커밋되지만 홀드 상태는 HELD로 남는 "반쪽 해제". 풀린 좌석이 다시
선점 가능해지며 **중복 홀드**와 **한도 카운트 오염**이 뒤따랐다.

## 4. 해결
홀드 상태를 벌크 UPDATE **전에** 확정 반영:
```java
hold.release();
holdRepository.saveAndFlush(hold);                 // 컨텍스트 클리어 전에 flush
seatRepository.releaseSeats(seatIds, AVAILABLE);   // 이후 벌크 UPDATE(클리어돼도 무관)
```
`saveAndFlush`로 홀드 RELEASED를 먼저 DB에 반영한 뒤 좌석을 푼다 → 두 상태가 항상 함께 이동.

**동반 FE 수정:** "선점 취소" 버튼이 `router.replace`로 **같은 경로**로 이동해 `held`
컴포넌트 상태가 리셋되지 않아 성공 화면에 머물렀다("취소해도 무반응"). 라우팅 대신
`setHeld(null)`+선택/타이머 초기화+재고 갱신으로 좌석 선택 화면에 복귀하도록 변경.

## 5. 재발 방지
- 통합 테스트 추가: 선점 해제 후 **좌석 AVAILABLE + 홀드 RELEASED**를 함께 단언하고,
  해제 후 같은 유저가 다시 선점 가능(한도 회복)함을 검증.
- 교훈: **`@Modifying(clearAutomatically=true)` 벌크 연산 뒤에서 로드해둔 엔티티를 mutate하지 말 것**
  (detached라 유실). 엔티티 변경은 벌크 연산 전에 flush하거나, 벌크 후 재조회해서 수행한다.
  [[TS-001]]과 같은 결 — "상태가 여러 저장소/연산에 나뉘면 함께 이동해야 한다".

## 한계 / 남은 것
- 기존에 오염된 데이터(반쪽 해제 홀드/중복)는 hold-ttl(5분) 만료 sweep로 정리되며, 검증 중 발견한
  event 2185 잔여는 수동 정리함. 능동적 정합성 점검(오프라인 리컨실)은 범위 밖.
