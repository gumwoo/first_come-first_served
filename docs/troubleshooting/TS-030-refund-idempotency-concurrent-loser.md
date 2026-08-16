# TS-030 · 환불은 동시 더블클릭에 멱등이 아니었다 — 테스트가 예외를 삼켜 가려져 있었다

- 슬라이스: S06(환불) — 발견은 2026-08-16, 멱등 테스트 강화 중
- 날짜: 2026-08-16
- 유형: 동시성 정확성 + **테스트가 결함을 가린 사례**
- 관련: [[IMP-008-payment-idempotency]], IMP-009(환불 멱등), [[TS-011]](조건부 전이 가드)
- 상태: **해결** — 전이 가드에서 멱등키를 재조회해 기존 결과를 반환

## 0. 무엇이 문제였나

같은 멱등키로 환불을 10회 동시 호출하면 **1건만 성공하고 9건이
`REFUND_NOT_ALLOWED`("환불할 수 없는 상태입니다")를 받았다.**

DB 최종 상태는 옳았다 — 환불 1건, 주문 REFUNDED, 좌석 AVAILABLE. 그래서 **오래
드러나지 않았다.** 피해를 보는 건 더블클릭한 사용자뿐이다. 돈이 걸린 화면에서
"환불할 수 없는 상태"를 보면 다시 누르거나 고객센터에 문의한다.

## 1. 왜 결제는 되고 환불은 안 됐나 — 순서가 반대였다

두 서비스는 같은 설계로 보인다. 멱등키 UNIQUE + 조건부 전이 + 충돌 시 기존 결과 반환.
**그런데 두 단계의 순서가 반대다.**

| | 멱등키 행 INSERT | 상태 전이 | 동시 패자가 만나는 것 |
|---|---|---|---|
| `PaymentService` | **먼저** | 나중 | UNIQUE 위반 → `pay()`가 잡아 **기존 결과 반환** |
| `RefundService` | **마지막** | 먼저 | 전이 가드 → **예외** |

```java
// RefundService.refundTx — 전이가 먼저다
int cancelled = orderRepository.markCancelled(orderId, OrderStatus.PAID);
if (cancelled != 1) {
    throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED); // ← 패자가 여기서 끝난다
}
...
refundRepository.save(...);   // 멱등키 UNIQUE는 여기 있는데, 패자는 도달하지 못한다
```

**멱등성을 UNIQUE 제약에 의존하는 설계는, 그 INSERT까지 도달해야만 작동한다.**
환불은 그 앞에 다른 가드를 하나 더 두면서 그 전제가 깨졌다. 두 가드 모두 각자로는
옳다 — 조합의 순서가 문제였다.

## 2. 왜 테스트가 못 잡았나 — 이 문서의 핵심

테스트는 있었다. `동시_더블클릭_같은키는_정확히_한번만_환불된다`. 통과하고 있었다.

```java
concurrent(10, i -> {
    try {
        refundService.refund(71L, c.orderId, "변심", key);
    } catch (Exception ignored) {
        // 멱등 처리로 예외 없어야 정상   ← 주석은 이렇게 적혀 있다
    }
    return 0;
});

assertThat(orderRepository.findById(c.orderId).orElseThrow().getStatus()).isEqualTo(REFUNDED);
assertThat(refundRepository.count()).isEqualTo(1);
```

**주석이 기대하는 것을 코드가 검증하지 않는다.** 그리고 뒤따르는 단언은 DB 최종
상태만 보는데, **그 상태는 1건만 성공해도 똑같이 만들어진다.** 9건이 터지든 0건이
터지든 통과한다.

멱등이 요구하는 것은 둘인데 테스트는 앞의 하나만 봤다.

| | 내용 | 기존 테스트 |
|---|---|---|
| ① | 부수효과는 한 번만 일어난다 | 검증함 |
| ② | **모든 호출이 같은 성공 결과를 돌려받는다** | 검증 안 함 |

②가 정확히 이 결함이 사는 자리다. **결함이 있는 쪽만 검증에서 빠져 있었다.**

## 3. 조치 — 가드에서 멱등키를 재조회한다

```java
if (cancelled != 1) {
    return refundRepository.findByIdempotencyKey(idemKey)
            .map(r -> RefundResponse.of(r, currentStatus(orderId).name()))
            .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_ALLOWED));
}
```

"취소의 주인이 아니다"와 "환불할 수 없다"는 **다른 상태**다. 전자는 같은 키의 동시
요청이면 정상이고, 후자만 예외다. 같은 키의 행이 없을 때만 던진다 — 다른 멱등키로
이미 환불된 주문은 여전히 `REFUND_NOT_ALLOWED`가 맞다.

### 재조회가 반드시 성공하는 근거

이 시점에는 **승자가 커밋을 마쳤다.** 조건부 UPDATE가 승자의 행 락에 걸려 대기하다가,
커밋 이후에야 `WHERE status = PAID`를 다시 평가해 0행으로 판정되기 때문이다
(PostgreSQL READ COMMITTED). 승자가 롤백했다면 이 UPDATE는 1행으로 **성공했을** 것이다.

즉 `cancelled != 1`은 "승자가 커밋했다"와 같은 말이고, 그러면 승자가 넣은 refunds
행도 함께 보인다.

## 4. 검증

- 강화한 테스트가 **고치기 전에 실패하는 것을 먼저 확인했다** — CI에서
  `REFUND_NOT_ALLOWED` 3건(10스레드 중). 실패를 보고 나서 고쳤다.
- 결제 쪽 같은 테스트는 같은 강화에도 **통과했다.** §1의 순서 차이가 원인이라는
  근거가 된다 — 두 서비스에 같은 잣대를 대고 한쪽만 걸렸다.

⚠️ 동시 호출 수는 10이고, 그보다 큰 동시성에서의 동작은 재지 않았다.

## 5. 배운 점

**최종 상태만 보는 동시성 테스트는 "몇 명이 성공했는가"를 못 본다.**
1명이 성공하든 10명이 성공하든 멱등 구현에서는 최종 상태가 같기 때문이다.
그래서 이 테스트는 통과하면서도 정확히 결함이 있는 축을 비워 두고 있었다.

> `catch (Exception ignored)` 옆에 "예외 없어야 정상"이라고 적혀 있다면,
> **그 주석은 검증되지 않은 주장이다.** 기대를 주석이 아니라 단언으로 옮겨야 한다.

그리고 **비슷해 보이는 두 구현을 같은 잣대로 재보는 것**이 유효했다. 결제와 환불은
설명이 같았지만("UNIQUE + 조건부 전이"), 같은 테스트를 씌우자 한쪽만 깨졌다.
설계 설명이 같다고 동작이 같지는 않다 — 단계의 **순서**까지 같아야 같다.
