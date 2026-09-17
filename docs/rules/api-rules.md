# API Rules

★ = 하네스 강제(`contracts/api.yaml`와 컨트롤러 diff).

## 1. URL 규약 ★
- 소문자 + 복수 리소스: `/events`, `/orders`
- 계층: `/events/{id}/seats/hold`
- 동사 금지(상태변경은 하위 리소스/액션으로): `POST /orders/{id}/payments`
- 관리자/개발자: `/admin/**`, `/dev/**`

## 2. 메서드 의미
- GET 조회 / POST 생성·실행 / PATCH 부분수정 / PUT 전체교체 / DELETE 삭제
- 멱등성: GET/PUT/DELETE 멱등, POST 비멱등

## 3. 응답 포맷 — 공통 래퍼 ★
```json
{ "data": { ... } }                                   // 성공
{ "error": { "code": "SOLD_OUT", "message": "..." } } // 실패
```
- 목록: `{ "data": { "items": [...], "page": 0, "size": 20, "total": 0 } }`
- 에러코드는 `contracts/error-codes.yaml` 등록값만 ★

## 4. 상태코드
- 200 조회/실행 성공, 201 생성, 204 본문없음
- 400 검증, 401 미인증, 403 인가, 404 없음, 409 충돌(매진/중복), 429 과다요청
- 선착순 특화: 매진(잔여 0) 409 + `SOLD_OUT`, 좌석 경합 409 + `SEAT_CONFLICT`,
  대기만료 410 + `QUEUE_EXPIRED`

### 입력 검증 실패는 400이다 — 예외 계층을 넓게 잡는다
- 검증 실패 핸들러는 하위 타입이 아니라 **`BindException`**으로 받는다.
  ```
  BindException                        ← @ModelAttribute + setter 바인딩(가변 빈)
    └─ MethodArgumentNotValidException ← @RequestBody, @ModelAttribute + 생성자 바인딩(record)
  ```
  "@ModelAttribute면 BindException"은 가변 빈에만 맞다. `PageQuery` 같은 record는 생성자 바인딩이라
  Spring 6.1이 `MethodArgumentNotValidException`을 던진다(MockMvc `resolvedException`으로 확인,
  `PaginationValidationTest`). 지금은 record만 있어 하위 타입만 잡아도 되지만, 가변 DTO가 추가되는 순간
  그쪽이 조용히 500으로 떨어지므로 상위 타입으로 받는다.
- `@RequestParam` + `@Validated` + `@Min` 방식은 쓰지 않는다. `ConstraintViolationException`을 던지는데
  전역 핸들러가 없어 500이 된다. 페이징은 `PageQuery` 값 객체로 받는다(하네스 backend 규칙 19).

## 5. 페이징/필터
- `?page=&size=` (0-base), 정렬 `?sort=field,asc`
- 관리자 목록은 필터 쿼리 일관(`status,event,from,to,q`)

## 6. 계약 변경 규칙 ★ (false positive 방지)
endpoint 추가/변경/삭제 시 **같은 PR에서** 함께 수정:
1. 컨트롤러 코드
2. `contracts/api.yaml`
3. FE `features/*/api` path fragment
4. (응답형 변경 시) BE DTO + FE 타입
5. 통합 테스트
→ 하나라도 누락되면 하네스 실패. 자세히는 git-pr-rules.md
