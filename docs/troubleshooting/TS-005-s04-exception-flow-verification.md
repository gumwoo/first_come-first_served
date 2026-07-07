# TS-005 · S04 예외 흐름 실검증 — 매진(SOLD_OUT) 트리거 + 대기만료 범위

- 슬라이스: `S04`(좌석·재고)
- 날짜: 2026-07-06
- 유형: 검증 로그(수동) + 부수 발견
- 관련 문서: [TS-001](TS-001-queue-reentry-after-expiry.md), [TS-002](TS-002-local-redis-version-zpopmin.md), [IMP-003](../improvements/IMP-003-oversell.md)

> 화면이 "빌드된다 ≠ 실제 조건에서 뜬다". 매진·대기만료 예외 화면을 실제 트리거까지 태워 확인하고, 상태는 원상복구.

## 1. 목적
좌석 선택의 예외 분기 두 개 — **매진 리다이렉트**와 **대기만료 리다이렉트** — 를 실제 조건으로
태워 검증한다. 검증을 위해 바꾼 상태(좌석/큐)는 반드시 원상복구한다.

## 2. 매진(SOLD_OUT) — 실트리거 검증 ✅
브라우저 확장 미연결로 UI 클릭 대신 **API 레벨로 동일 조건**을 만들어 태웠다(userId=4, event=48).

절차:
1. `POST /events/48/queue/token`(Bearer) → 입장토큰 발급(WAITING rank 1).
2. 승격 워커 대기(약 3초) → `GET /queue/status` = **ADMITTED**. (TS-002 Redis 교체 효과도 재확인)
3. 대상 좌석을 `update seats set status='HELD'`로 전환(매진 조건 생성).
4. `POST /events/48/seats/hold`(입장토큰) → **`409 {"code":"SOLD_OUT","message":"매진되었습니다."}`**.
5. 좌석을 `AVAILABLE`로 복구.

결과: 재고가 없을 때 선점이 **실제로 SOLD_OUT을 반환**함을 실증. 프론트는 이 코드를 받아
`router.replace('/events/{id}/sold-out')`로 매핑(코드 확인). 매진 페이지 자체 렌더는 별도 확인됨.

## 3. 대기만료 — 범위와 한계
- `/events/{id}/seats/expired` 페이지 **렌더 확인됨**(HTTP 200 + "좌석 선택 시간이 만료"/"대기열 다시 진입").
- 다만 "타이머 0초 → `router.replace('/seats/expired')`"는 **순수 클라이언트 타이머**라 헤드리스(API)로는
  실행 불가. 실제 UI 리다이렉트 확인은 **브라우저 확장 연결 시 후속**(SELECT_SECONDS를 잠깐 낮춰 관찰 후 복구).
- 백엔드 측 관련 불변식(입장창 만료 후 선점 거부)은 통합 테스트로 커버됨.

## 4. 원상복구 확인
- 좌석: event 48 전량 `AVAILABLE`(100/100).
- 큐: `leave`(token 포함)로 토큰 정리, `queue:admitcount:48 = 0`. 잔여 `queue:seq:48`은 무해한 카운터.
- 워킹트리/DB 원본 유지.

## 5. 부수 발견 (별건, 후속)
- `DELETE /queue/token`에 필수 `token` 파라미터를 누락하면 **400이 아니라 500(INTERNAL_ERROR)** 이 반환됨.
  `MissingServletRequestParameterException`이 공통 예외 핸들러의 일반 500으로 떨어지는 것으로 추정.
  기능 결함은 아니나 API 위생상 400이 맞음 → 후속 과제로 분리(공통 핸들러에 400 매핑 추가).

## 한계 / 남은 것
- 매진의 "UI 클릭 → 리다이렉트" 전 구간, 대기만료의 타이머 리다이렉트는 브라우저 확장 연결 시
  경로 A(실 UI)로 마저 태우면 완결. 현재는 백엔드 트리거 + 페이지 렌더 + 코드 매핑까지 확인.
