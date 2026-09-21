# ADR-019 · 자기 프록시 주입 대신 협력자로 나눈다

- 상태: **Accepted** (전 구간 적용 완료 · 하네스 규칙 ㉑로 재발 차단)
- 날짜: 2026-09-21
- 슬라이스: 횡단(S01·S02·S04·S05·S06)
- 관련: [[TS-014]](사전 검사가 동시 요청에 뚫리는 같은 패턴), [[TS-021]](KOPIS 트랜잭션 범위), [[ADR-011]](정산 쓰기 협력자)

## 맥락

스프링의 `@Transactional`·`@SchedulerLock`은 프록시로 동작한다. 같은 객체 안에서 `this.method()`로
부르면 프록시를 지나지 않아 **어노테이션이 적용되지 않는다.** 이 저장소는 그 회피로 자기 자신을
주입받아 왔다.

```java
private final ObjectProvider<AuthService> self; // 트랜잭션 프록시 self-호출용
...
self.getObject().signupTx(req);
```

동작은 맞다. 문제는 **프레임워크의 구현 방식이 서비스의 의존성 목록에 드러난다**는 것이다. 클래스가
자기 자신을 필드로 들고, 단위 테스트는 `@Mock ObjectProvider<AuthService>`를 넣고 "자기 자신을
돌려주도록" 배선해야 한다. 경계를 나눈 **이유**(트랜잭션 밖에서 예외를 잡는다, 락을 공통 진입점에
건다)는 주석에만 남고 구조에는 없다.

## 결정

**경계가 필요하면 빈을 나눈다.** 호출이 다른 빈을 지나면 프록시가 정상 적용되고, 경계가 호출 관계로
드러난다. 이미 이 저장소에 있던 형태다(`KopisDetailWriter`, `RefundConverger`).

나누는 방향은 경계의 성격을 따른다.

| 경계 | 나누는 방향 | 예 |
|---|---|---|
| 트랜잭션 **안**의 쓰기 | 쓰기 전용 협력자 | `UserRegistrar`, `RefundConverger` |
| 트랜잭션 **밖**의 진입점 | 진입점을 분리 | `KopisSyncScheduler` |
| 트랜잭션 경계가 **알고리즘 자체** | `TransactionTemplate` | `OrderService`·`PaymentService`·`RefundService` |
| 읽기/쓰기의 일관성 모델이 다름 | 조회·명령 분리 | `SeatService`(후속) |

**락·트랜잭션의 위치는 옮기지 않는다.** KOPIS의 `@SchedulerLock`은 `sync()`에 그대로 둔다. 예전에
락이 스케줄 메서드에만 있어 수동 API가 우회한 적이 있고, 그래서 공통 진입점으로 내렸다. 자기 주입을
없애자고 그 결정을 되돌리면 같은 결함이 돌아온다 — 분리하는 것은 **스케줄 진입점**이지 락이 아니다.

## 고려한 대안

- **`TransactionTemplate`으로 바꾼다.** Auth·KOPIS에는 쓰지 않았다. 그 둘은 경계가 메서드 단위로
  깔끔하게 갈려 협력자 분리로 충분하다. **2단계(Order·Payment·Refund)에는 이쪽을 썼다** —
  아래 §2단계 참고.
- **`@Transactional(propagation = REQUIRES_NEW)`로 안쪽을 새 트랜잭션으로 연다.** 자기 주입은 그대로
  남는다. 해결이 아니다.
- **AspectJ 로드타임 위빙으로 self-invocation도 가로챈다.** 빌드·기동에 위빙을 더한다. 문제 하나를
  피하려고 런타임 구성을 늘리는 쪽이 비싸다.

## 적용 순서

위험도 순으로 나눈다. 한 PR에 몰면 결제·환불의 동시성 불변식([[TS-011]]·[[TS-030]])까지 한 번에
재검증해야 한다.

1. **Auth·KOPIS**(완료) — 가입 트랜잭션, 스케줄 진입점. 동시성 불변식과 무관하다.
2. **Order·Payment·Refund**(완료) — `TransactionTemplate`. 조건부 전이·멱등키 순서를 함께 검증했다.
3. **Seat**(완료) — 조회(캐시, `NOT_SUPPORTED`)와 명령(홀드, advisory lock)의 일관성 모델이 다르다.
   클래스 분리가 곧 자기 주입 제거가 됐다. `SeatQueryService`(캐시) → `SeatMapLoader`(읽기 트랜잭션),
   `SeatService`(선점·해제), 양쪽이 같은 가격을 보도록 `SeatPricing`을 공유한다.

## 2단계 · 주문·결제·환불에 `TransactionTemplate`을 쓴 이유

이 셋은 형태가 같다. **트랜잭션을 열고, 커밋에서 나는 제약 위반을 경계 밖에서 잡아 복구한다.**

```java
try {
    return tx.execute(status -> payTx(...));   // 멱등키 UNIQUE는 커밋에서 터진다
} catch (DataIntegrityViolationException e) {
    return paymentRepository.findByIdempotencyKey(idemKey)...  // 승자의 결과를 돌려준다
}
```

여기서는 **경계가 곧 알고리즘**이다. "언제 커밋되는가"와 "실패를 어디서 잡는가"가 이 메서드가 하는
일의 핵심이라, 어노테이션으로 숨기는 것보다 코드로 보이는 편이 낫다. 협력자로 빼면 그 관계가 다시
두 클래스에 흩어진다.

`PaymentService`에는 실질적인 이유가 하나 더 있다. 트랜잭션 메서드가 넷(`payTx`·`confirmTx`·
`confirmVbankDeposit`·`handleVbankDepositWebhook`)인데 `finalizePaid`·`appendOutbox`·`ownedOrder`를
함께 쓴다. 앞의 둘만 협력자로 빼면 그 사설 헬퍼들이 두 클래스로 갈라지거나, 넷 다 옮겨 서비스가
껍데기만 남는다.

본문 메서드는 `private`이 된다. 부수 효과로 **경계를 건너뛰고 본문을 직접 부를 길이 사라진다** —
예전에는 `payTx`가 `public`이라 누군가 트랜잭션 없이 호출할 수 있었다.

`TransactionTemplate` 빈은 스프링 부트가 자동 구성한다(`TransactionAutoConfiguration`,
`PlatformTransactionManager`가 하나일 때). 이 저장소는 JPA 트랜잭션 매니저 하나뿐이다.

## 결과 / 한계 (정직)

- **동작은 바뀌지 않는다.** 트랜잭션 경계·락 위치·예외 처리 지점이 모두 같다. 얻는 것은 구조와
  테스트 배선이다(`@Mock ObjectProvider` 2개가 사라졌다).
- **클래스 수가 는다.** 작은 협력자가 도메인마다 하나씩 생긴다. 자기 주입보다 낫다고 판단했지만
  공짜는 아니다.
- **하네스 규칙 ㉑로 막았다.** 파일명과 같은 타입을 `ObjectProvider`로 주입하면 실패한다. 다른 빈을
  `ObjectProvider`로 받는 것(지연 조회·선택 주입)은 정당한 쓰임이라 건드리지 않는다.
- **`TransactionTemplate`은 어노테이션보다 읽는 사람이 적다.** 이 저장소에서 처음 쓰는 형태라,
  "본문이 `private`이고 경계는 호출부에 있다"는 규칙이 지켜지는지는 리뷰가 봐야 한다.
