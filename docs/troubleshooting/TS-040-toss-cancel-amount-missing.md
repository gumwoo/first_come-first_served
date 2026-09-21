# TS-040 · 수수료를 뗀 환불이 전액 취소로 나갈 수 있었다 — 취소 금액을 계산해 놓고 PG에 보내지 않았다

- 슬라이스: S06(환불) — 코드 리뷰에서 발견
- 날짜: 2026-09-19
- 유형: 결함 수정 — 어댑터가 인자를 버려 내부 장부와 외부 상태가 어긋난다
- 관련: `TossPaymentGateway`, `TossClientConfig`, `RefundPolicy`, [[TS-028]], [[ADR-005]]
- 상태: **해결** — 취소 금액을 본문에 싣고, 어댑터가 보내는 요청을 테스트로 고정했다

## 1. 증상

배포 환경에서 재현된 사건이 아니라 코드 리뷰에서 나왔다. 실 PG로 확인하지는 못했고,
아래는 코드와 Toss 결제취소 API 계약으로 판정한 내용이다.

환불 금액은 경로 전체에서 올바르게 계산된다.

```java
// RefundPolicy.quote() — 100,000원 결제를 D-5에 취소하면
int fee = paidAmount * rate / 100;              // 10,000
return new RefundQuote(rate, fee, paidAmount - fee, true);  // 환불 90,000

// RefundService.refundTx()
ApproveResult res = gateway.refund(pgTid, q.refundAmount());  // 90,000을 넘긴다
```

그런데 어댑터가 그 값을 쓰지 않았다.

```java
// TossPaymentGateway.refund(String pgTid, int amount) — 수정 전
.body(Map.of("cancelReason", "고객 취소"))   // amount가 본문에 없다
```

Toss 결제취소 API는 `cancelAmount`가 없으면 전액 취소로 처리한다. 그러면 `refunds` 행에는
90,000원과 수수료 10,000원이 남는데 고객에게는 100,000원이 돌아간다. 주문·좌석 상태는
정상으로 수렴하므로 에러도, 로그도 남지 않는다.

## 2. 왜 지금까지 안 걸렸나

두 가지가 겹쳤다.

**환불 테스트는 Mock 게이트웨이를 쓴다.** `MockPaymentGateway.refund()`는 금액을 보지 않고
항상 성공을 돌려준다. 그래서 어댑터가 금액을 통째로 버려도 `RefundIntegrationTest`는 통과한다.
기존 테스트가 검증한 것은 "환불 흐름의 상태 전이"였고, "PG에 무엇을 보내는가"는 아무도 보지
않았다.

**Toss 어댑터는 배포 환경에서만 활성이다.** `application.yml`의 기본값은 `mock`이고
`infra/docker-compose.prod.yml`도 `mock`인데, `k8s/base/configmap.yaml`만
`PAYMENT_GATEWAY: "toss"`다. 로컬·CI에서는 이 코드가 아예 실행되지 않는다.

> 어댑터를 Mock으로 대체해서 도메인 흐름을 테스트하면, **대체한 그 어댑터 자체는 아무도
> 테스트하지 않는 상태**가 되기 쉽다. 포트가 경계를 잘 갈라 놓을수록 경계 바깥이 비어 보인다.

## 3. 해결

취소 금액을 본문에 싣고, 0 이하는 PG까지 보내지 않는다.

```java
.body(Map.of("cancelReason", "고객 취소", "cancelAmount", amount))
```

금액을 검증하려면 어댑터가 실제로 보내는 HTTP 요청을 봐야 하는데, 기존 구조로는 볼 수 없었다.
`TossPaymentGateway`가 생성자에서 `requestFactory(...)`로 요청 팩토리를 덮어써,
`MockRestServiceServer.bindTo(builder)`가 끼워 넣은 팩토리를 지워 버리기 때문이다.

그래서 클라이언트 구성을 `TossClientConfig`로 옮겼다. `KopisClientConfig`가 이미 쓰고 있던
형태이고, 타임아웃([[TS-028]])은 그대로 유지된다. 하네스 규칙 ⑱은 주입 빌더 형태도 보므로
옮긴 뒤에도 계속 잡는다.

## 4. 재발 방지

`TossPaymentGatewayTest`가 요청 본문을 직접 검증한다. 수수료를 뗀 부분 취소, 전액 취소,
0원 취소, 승인 확정 네 가지다.

**고친 것을 되돌려 규칙이 우는지 확인했다**([[TS-028]] §3과 같은 절차). `cancelAmount`를
본문에서 빼자 4건 중 2건이 실패했고, 되돌리자 다시 통과했다.

정적 규칙은 만들지 않았다. "인자를 받아 놓고 쓰지 않는다"를 정규식으로 판정하면 오탐이
과도하다(`MockPaymentGateway`가 정당한 예다). 어댑터가 보내는 것은 그 어댑터의 테스트로
고정하는 편이 싸고 정확하다.

## 5. 한계

- **실 Toss 테스트 계정으로 확인하지 못했다.** 부분 취소가 실제로 90,000원만 취소하는지는
  API 계약에 근거한 판단이고, 측정한 값이 아니다.
- ~~**PG 취소 요청에 멱등키를 보내지 않는다.**~~ → 해결. 멱등키를 PG까지 전달한다([[ADR-011]] 추가 결정).
- ~~**환불 정산은 없다.**~~ → 해결. `Inquiry`가 PG 상태를 구분하게 넓히고
  `RefundReconciliationService`를 붙였다([[ADR-011]] 추가 결정).
