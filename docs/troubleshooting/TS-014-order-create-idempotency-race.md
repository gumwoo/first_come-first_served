# TS-014 · 같은 hold로 동시에 주문하면 둘 다 만들어짐 — 앱의 "찾고→없으면 생성"이 뚫린다

- 슬라이스: S05(주문/결제) · 횡단
- 날짜: 2026-08-06
- 유형: 정합성 버그(코드)
- 관련 PR: [#168](https://github.com/gumwoo/first_come-first_served/pull/168),
  [#171](https://github.com/gumwoo/first_come-first_served/pull/171)
- 상태: 해결

## 1. 증상

**사용자 신고나 실패한 테스트에서 시작하지 않았다.** [TS-013](TS-013-per-user-quota-aggregate-invariant.md)
말미에 적어 둔 문장에서 시작했다.

> "같은 패턴(읽고 → 검사 → 행위)이 다른 곳에도 있을 수 있다."

그 문장을 실제로 점검했다.

## 2. 조사 — 같은 저장소 안에 방어 수준이 네 갈래였다

| 경로 | 방어 | 판정 |
|---|---|---|
| 대기열 1인1토큰 | Lua 원자화 | ✅ 애초에 이 패턴이 아님 |
| 결제·환불 멱등 | UNIQUE + `DataIntegrityViolationException` 캐치 | ✅ 정석 |
| 가입 | UNIQUE만, 캐치 없음 | 🟠 정합성 OK, **500이 나감** → [TS-015](TS-015-truncate-scheduler-deadlock.md) 이전에 PR #169로 처리 |
| **주문 생성** | **아무것도 없음** | 🔴 이 문서 |

```java
// 멱등: 같은 hold로 이미 활성 주문이 있으면 그대로 반환(더블 POST 방어)
return orderRepository.findFirstByHoldIdAndStatusIn(holdId, ACTIVE)   // 검사
        .orElseGet(() -> toResponse(build(userId, hold)));             // 행위
```

주석은 "더블 POST 방어"라고 적혀 있지만 **정확히 동시에 오면 둘 다 "없음"을 보고 각자 INSERT**한다.
그리고 `orders`에는 `hold_id` UNIQUE가 **없었다** — `users.email`·`payments.idempotency_key`·
`refunds.idempotency_key`에는 있는데 여기만 비어 있었다.

## 3. 근본 원인

**앱의 검사는 UX용이고 최종 방어선은 DB 제약이어야 한다**는 원칙이 이 경로에만 적용되지 않았다.
"찾고 → 없으면 생성"은 **순차 요청만** 막는다.

**파급**: 이중 판매는 나지 않는다(두 번째 결제가 좌석 조건부 UPDATE에서 0행으로 롤백).
다만 **그 전에 PG 승인이 나갔다면 미아 승인**이 남아 [ADR-011](../decisions/ADR-011-payment-reconciliation.md)
정산이 회수해야 하고, 마이페이지에 같은 좌석 주문이 둘 보인다.

## 4. 해결

### 부분 UNIQUE 인덱스 (V15)

```sql
CREATE UNIQUE INDEX uq_orders_active_hold ON orders (hold_id)
 WHERE status IN ('PENDING', 'VBANK_WAITING');
```

전체를 묶으면 **만료·취소 뒤 재주문이 막힌다.** 활성일 때만 hold당 1건을 강제한다.

### 제약 위반 → 멱등 반환

```java
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // ← 반드시 필요
public OrderResponse create(Long userId, Long holdId) {
    try {
        return self.getObject().createTx(userId, holdId);
    } catch (DataIntegrityViolationException e) {
        return orderRepository.findFirstByHoldIdAndStatusIn(holdId, ACTIVE)
                .map(this::toResponse)
                .orElseThrow(() -> e);   // 아는 제약이 아니면 원 예외를 그대로
    }
}
```

## 5. 이 과정에서 CI가 잡아낸 것 2건

**둘 다 리뷰가 지적하고 CI가 증명했다.** 로컬에 gradle이 없어 백엔드 검증 수단이 CI뿐이었다.

### 🔴 트랜잭션 경계가 실제로는 분리되지 않았다 — 44개 테스트 실패

결제·환불의 형태(UNIQUE + 캐치)를 따라 썼는데, **캐치를 트랜잭션 밖에 두는 부분에서 실패했다.**

```java
@Transactional(readOnly = true)   // ← 클래스 레벨
public class OrderService {
    public OrderResponse create(...) {        // → 읽기 전용 트랜잭션을 상속
        self.getObject().createTx(...);        // → REQUIRED가 새로 만들지 않고 거기 참여
```

결과 (1) INSERT가 read-only 트랜잭션에서 실패 (2) 경계가 안 나뉘어 캐치도 무의미.
`@Transactional(propagation = NOT_SUPPORTED)`로 고쳤다.

> **재발 방지**: 클래스 레벨 `@Transactional`이 있는 곳에서 self-호출로 경계를 나눌 때 반복될
> 함정이라 `OrderService`·`AuthService` 양쪽 주석에 "⚠️ 반드시 필요" 근거를 남겼다.

### 원인을 잃어버리던 경로 (PR #171)

```java
.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));  // ← e가 버려짐
```

조회가 비었다는 건 위반의 정체가 그 인덱스가 **아니라는** 뜻이다(NOT NULL·FK 등 진짜 버그).
그걸 도메인 예외로 갈아끼우면 **로그에 스택이 한 줄도 안 남는다** — `GlobalExceptionHandler`가
`BusinessException`을 전용 분기에서 먼저 잡아 `log.error`를 타지 않기 때문이다.
"응답 500 + 로그 무(無)"가 된다.

## 6. 재발 방지

- **테스트**: 4스레드 동시 생성 — `500 없음` + `모든 요청이 주문을 돌려받음` + `주문은 하나`.
  "예상 실패를 삼키면 3건이 예외로 끝나고 1건만 성공해도 통과한다"는 함정을 피해
  **`created.hasSize(threads)`** 를 단언한다.
- **단위 테스트**(`OrderServiceTest`): 아는 제약이 아니면 **원본과 같은 인스턴스**(`isSameAs`)가
  올라오는지 — 타입만 보면 새로 감싼 예외도 통과한다.
- **규칙**: 제약 위반을 도메인 예외로 번역하는 것은 **정체를 확인한 것만**. 나머지는 손대지 않는다.
  가입(PR #169)에도 같은 규칙을 적용했다.
- **롤링 배포 주의**를 마이그레이션 주석에 남겼다 — 인덱스가 먼저 반영되고 구버전 Pod가 살아 있으면
  구버전이 중복을 만들 때 제약 위반이 500으로 나간다(중복 데이터보다는 낫고 창이 짧아 감수).
