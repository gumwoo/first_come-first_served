# TS-010 · 가상계좌 발급 정보(secret/계좌/기한)가 DB에 안 남음 — 벌크 UPDATE 컨텍스트 클리어 (TS-007 재발)

- 슬라이스: `S05`(주문·결제)
- 날짜: 2026-07-13
- 유형: 정합성 버그(코드) — JPA 영속성 컨텍스트
- 관련 커밋/PR: PR #87(가상계좌 입금 웹훅)
- 관련 문서: [[TS-007]](같은 근본 원인, S04 선점 해제), [[ADR-005]](웹훅 secret 검증), [[ADR-006]]

> 순서: 증상 → 조사 → 근본 원인 → 해결 → 재발 방지.

## 1. 증상
가상계좌 입금 웹훅(`DEPOSIT_CALLBACK`)의 secret 검증 통합테스트 3종이 CI에서 실패.
전부 `handleVbankDepositWebhook(...)` 호출부에서 `BusinessException`:
```
PaymentWebhookIntegrationTest > 정상_입금웹훅은_...  FAILED (line 112)
PaymentWebhookIntegrationTest > 재전송_웹훅은_멱등_... FAILED (line 138)
PaymentWebhookIntegrationTest > 입금완료_아닌_status는_대기_유지() FAILED (line 152)
```
반면 **위조 secret 거부** 테스트(잘못된 secret으로 호출 → throw 기대)는 **통과**.

## 2. 조사
핵심 단서는 "미완료 status" 테스트(올바른 secret, status=`WAITING_FOR_DEPOSIT`)의 실패였다.
이 경로는 핸들러 설계상 `status != DONE`이면 조용히 `return`해야 하는데 예외가 났다 →
**status 게이트에 도달하기 전에** 던졌다는 뜻. 그 앞의 유일한 throw는 secret 검증:
```java
if (payment.getVbankSecret() == null || !payment.getVbankSecret().equals(secret))
    throw new BusinessException(ErrorCode.FORBIDDEN);
```
위조 테스트가 통과한 것도 같은 원인으로 설명된다 — `getVbankSecret()`이 **null**이면 위조든
정상이든 모두 FORBIDDEN. 즉 **저장된 secret이 null**. 발급 시 넣은 값이 DB에 안 남았다.

## 3. 근본 원인
`PaymentService.payTx()`의 vbank 분기가 **TS-007과 동일한 순서 문제**를 갖고 있었다:
```java
payment.assignVbank(account, deadline, secret); // 엔티티 mutate (아직 flush 안 됨)
paymentRepository.save(payment);                // 관리 엔티티 save — flush 강제 아님
orderRepository.markVbankWaiting(orderId);      // @Modifying(clearAutomatically=true)
```
`markVbankWaiting`은 `orders`를 치는 벌크 UPDATE라, `payments`의 dirty 상태는 자동 flush
대상이 아니다(테이블이 달라 Hibernate가 쿼리 전 flush를 건너뜀). 그런데 `clearAutomatically=true`가
컨텍스트를 비우며 **아직 안 써진 payment 변경(secret·계좌·기한)을 detach → 유실**.

카드 경로(`finalizePaid`)는 벌크 `markPaid` 전에 `saveAndFlush(payment)`로 이미 방어돼 있었는데,
vbank 경로만 이 방어가 빠져 있었다. 기존 테스트는 발급 후 계좌/기한을 **DB에서 다시 읽지 않아서**
잠복해 있었고(응답값 `vb.account()`만 확인), secret을 DB에서 재조회하는 웹훅 테스트가 드러냈다.

## 4. 해결
벌크 UPDATE 전에 payment를 flush로 확정:
```java
payment.assignVbank(vb.account(), order.getExpiresAt(), vb.secret());
paymentRepository.saveAndFlush(payment);   // 컨텍스트 클리어 전에 계좌/기한/secret 확정
orderRepository.markVbankWaiting(orderId); // 이후 벌크 UPDATE(클리어돼도 무관)
```
secret뿐 아니라 그동안 잠복해 있던 **계좌번호·입금기한 유실**도 함께 고쳐진다.

## 5. 재발 방지
- 웹훅 통합테스트가 발급 정보를 **DB에서 재조회**해 단언하도록 유지(응답값만 믿지 않기).
- 교훈(재확인): **`@Modifying(clearAutomatically=true)` 벌크 연산 앞에서 로드/변경한 엔티티는
  반드시 먼저 flush**한다. TS-007에서 S04에 배운 것을 S05 결제 경로에도 일괄 점검했어야 했다 —
  같은 패턴(`save`+바로 벌크)이 남아 있는지 도메인 전체를 훑는 게 근본 재발 방지책.
- 후보 가드(백로그): 하네스에 "`@Modifying(clearAutomatically)` 호출 직전 라인에서 같은 트랜잭션의
  엔티티 mutate 후 `saveAndFlush` 없이 벌크를 부르는 패턴" 정적 탐지 추가 검토.

## 한계 / 남은 것
- 실 Toss 가상계좌 발급(결제창)은 아직 미배선이라, 실제 Toss가 주는 secret 저장은 라이브 배선 시 확인.
- 정적 가드(위 백로그)는 오탐 위험이 있어 설계 후 도입 — 이번 PR 범위 밖.
