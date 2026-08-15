# TS-028 · 결제 PG 호출에 타임아웃이 없었다 — KOPIS는 지키고 결제는 안 지킨 규율

- 슬라이스: S05(결제) — 발견은 2026-08-13 리뷰
- 날짜: 2026-08-13
- 유형: 결함 발견 → 수정 — **같은 규율을 한쪽에만 적용했다**
- 관련: [[TS-021]](Hikari 풀 5), [[TS-026]](같은 형태 — 한쪽에 이미 정답이 있었다),
  [ADR-011](../decisions/ADR-011-payment-reconciliation.md)(미아 승인 회수)
- 상태: **해결** — 타임아웃을 걸고 하네스 규칙 ⑱로 재발을 막았다

## 0. 무엇이 문제였나

```java
// TossPaymentGateway — 수정 전
this.client = RestClient.builder().baseUrl(BASE_URL).build();
```

**request factory가 없다 = 타임아웃이 없다.** 그리고 이 사실은 이미 이 저장소에 적혀 있었다.
`KopisClientConfig`의 클래스 주석이다.

> ⚠️ 지정하지 않으면 사실상 무한 대기다. RestClient는 request factory를 주지 않으면
> 클래스패스에서 고르는데, 이 이미지에는 Apache HttpClient5·Jetty·OkHttp가 없어(파드에서 확인)
> JDK HttpClient로 떨어진다. **JDK HttpClient는 connect/request 타임아웃 기본값이 없다.**

**KOPIS는 두 개의 클라이언트에 각각 타임아웃을 걸어 두었는데(detail 2s/3s, sync 2s/10s),
결제만 안 걸려 있었다.** 새로 알아야 할 것이 없었다 — 이미 확인된 사실을 다른 경로에 적용하지
않은 것뿐이다.

## 1. 왜 결제가 KOPIS보다 나쁜가

KOPIS가 멈추면 톰캣 스레드가 묶인다. **결제가 멈추면 거기에 DB 커넥션이 더해진다.**

```
PaymentService.payTx / confirmTx   @Transactional
RefundService.refund               @Transactional
      └─ gateway.confirm() / refund()   ← 트랜잭션 안에서 외부 호출
```

트랜잭션이 열린 상태로 외부를 기다리므로 Hikari 커넥션을 붙잡는다. 풀은 **파드당 5**다
(`DB_POOL_MAX`, [[TS-021]]에서 10→5로 줄였다 — RDS 슬롯 상한 때문). 응답 없는 PG 요청 5건이면
**그 파드의 DB가 멎는다.** 결제만 막히는 게 아니라 같은 파드의 모든 DB 접근이 막힌다.

그리고 readiness는 DB·Redis만 보므로 파드는 계속 UP이다. K8s가 빼주지 않는다 —
[[TS-020]]에서 본 **"에러 없이 조용히 멈추는"** 실패 형태다.

⚠️ **이 고갈이 실제로 발생한 관측 기록은 없다.** 코드 경로와 설정값(풀 5, 타임아웃 없음)으로
확인한 것이고, 결제 경로에 부하를 준 측정은 하지 않았다. Phase 1에서 본 `Hikari pending 611`은
**조회 부하**였다.

## 2. 조치 — 타임아웃을 건다

`KopisClientConfig`와 같은 방식으로, 주입받은 빌더를 복제하고 request factory를 준다.

```java
this.client = builder.clone().baseUrl(BASE_URL)
        .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))   // 기본 2s
                .withReadTimeout(Duration.ofMillis(readTimeoutMs))))       // 기본 10s
        .build();
```

### 값 선택 — read 타임아웃은 새 실패 유형을 만든다

**타임아웃은 "승인 안 됨"이 아니라 "모름"이다.** Toss는 승인했는데 우리가 응답을 못 받았을 수
있다. 그러면 트랜잭션이 롤백돼 DB에 흔적이 없는 **미아 승인**이 된다.

즉 **타임아웃을 거는 행위 자체가 미아 승인을 만들 수 있다.** 그런데 그 클래스를 회수하려고 만든
장치가 이미 있다 — [ADR-011](../decisions/ADR-011-payment-reconciliation.md) 정산 잡이
`orders`의 `PENDING`/`EXPIRED`를 후보로 잡아 PG 조회 후 취소한다.

> **이 타임아웃은 정산 잡이 있기 때문에 안전하다.** 없었다면 무한 대기와 미아 승인 중
> 하나를 고르는 문제였을 것이다.

반대로 값이 짧으면 **멀쩡한 결제를 미아로 만든다.** 그래서 KOPIS 사용자 경로(3초)보다 넉넉한
10초로 잡았다. 둘 다 `TOSS_CONNECT_TIMEOUT`·`TOSS_READ_TIMEOUT`으로 조정 가능하다.

### 남긴 것 — 트랜잭션 구조는 건드리지 않았다

리뷰는 "외부 호출이 DB 트랜잭션을 붙잡는다"도 함께 지적했고 그것도 사실이다. **그러나
그 재구조화는 하지 않았다.**

가장 자연스러운 개선(PG 호출 전에 "시도" 행을 별도 트랜잭션으로 선기록)은 **ADR-011이 이미
검토하고 기각한 대안**이다.

> **의도 로그(intent log)를 별도 트랜잭션으로 선기록**: … 그러나 **결제 핫패스에 쓰기가 하나
> 늘고**, 그 쓰기 자체가 실패하는 창이 또 생긴다. 주문이라는 durable 신호가 이미 있으므로
> 추가 쓰기 없이 후보를 만들 수 있다 → 기각

기록된 결정을 근거 없이 뒤집지 않는다. 대신 **타임아웃이 이 문제의 성격을 바꾼다** —
커넥션 점유가 **무한에서 유한(최대 connect 2s + read 10s)** 으로 바뀐다. 트랜잭션이 외부를
기다리는 구조는 남지만, 그 대기가 바운드된다.

⚠️ **바운드됐다는 것이 충분하다는 뜻은 아니다.** 12초 × 동시 5건이면 여전히 그 파드의 풀이
그동안 마른다. 재구조화가 필요해지는 시점의 판단 근거는 **결제 경로 부하 측정**이고,
그 측정은 하지 않았다.

## 3. 재발 방지 — 하네스 규칙 ⑱

타임아웃은 런타임 테스트로 잡기 나쁘다. 재현하려면 **응답하지 않는 서버**가 필요해서 느리고
불안정하다. 정적으로는 싸게 잡힌다.

```
외부 HTTP 클라이언트에 타임아웃이 없다: …/TossPaymentGateway.java —
  RestClient를 만들면서 requestFactory(...)로 connect/read 타임아웃을 주지 않았다.
```

**규칙을 만들고 원래 버그로 되돌려 실제로 잡는지 확인했다.** 잡았다.

### 초안이 자기 fixture를 못 잡았다

첫 버전은 원문 그대로 `raw.includes("requestFactory(")`를 봤는데, **위반 fixture의 javadoc에
그 단어가 들어 있다는 이유만으로 통과했다.**

```java
/** 위반 fixture: RestClient를 만들면서 requestFactory(타임아웃)를 주지 않는다. */
```

**주석에 이름을 언급하는 것과 실제로 호출하는 것은 다르다.** 주석을 걷어내고 보도록 고쳤다.

### 그리고 규칙이 **자기가 만든 수정을 못 보게 됐다**

초안은 클라이언트를 만드는 형태를 `RestClient.builder()` / `RestClient.create()` 하나로만 봤다.
그런데 **이 TS의 수정이 Toss를 다른 형태로 바꿨다.**

```java
// 수정 전 — 규칙의 시야 안
this.client = RestClient.builder().baseUrl(BASE_URL).build();

// 수정 후 — 규칙의 시야 밖
this.client = builder.clone().baseUrl(BASE_URL).requestFactory(...).build();
```

`builder.clone()`에는 `RestClient.builder(`가 없다. **고치는 순간 그 파일이 규칙에서 사라졌다.**
누군가 나중에 `requestFactory(...)`만 지워도 하네스는 통과한다 — 즉 **재발 방지 규칙이 정작
이 사건의 재발을 못 막는 상태**였다.

리뷰에서 잡혔고, 말로 따지지 않고 확인했다. `requestFactory`만 제거하고 하네스를 돌리자
**통과했다.** 그래서 규칙이 두 형태를 다 보게 고쳤다.

| 형태 | 예 |
|---|---|
| ① 직접 만든다 | `RestClient.builder(...)` · `RestClient.create(...)` |
| ② 주입 빌더를 쓴다 | `RestClient.Builder`를 받아 `.build()` |

고친 뒤 같은 절차를 반복해 **정확히 `TossPaymentGateway`를 지목해 실패**하는 것을 확인했고,
②를 위한 fixture(`be-http-no-timeout-injected`)를 추가해 메타테스트에 등록했다.

> 여기서 아이러니한 것은, **자기 fixture에 속은 것(주석)**과 **자기 수정에 눈이 먼 것(형태 변경)**이
> 같은 규칙에서 연달아 나왔다는 점이다. 규칙을 쓸 때는 "무엇을 잡느냐"만이 아니라
> **"내가 방금 바꾼 코드를 여전히 보고 있느냐"**를 확인해야 한다. 그 확인 방법은 하나뿐이다 —
> **고친 것을 되돌려 보고 규칙이 우는지 본다.**

[[TS-025]]에서 규칙을 세 번 좁혀야 했던 것과 같은 종류의 일이고, 이번에도 **fixture와 리뷰가
규칙의 결함을 먼저 잡았다.**

## 4. 배운 점

**[[TS-026]]과 같은 형태다.** 거기서는 `OutboxRelay`가 이미 `.get()`으로 브로커를 확인하고
있었는데 `AdminDlqService`만 안 하고 있었다. 여기서는 `KopisClientConfig`가 이미 타임아웃의
필요성을 **주석으로 적어두기까지** 했는데 `TossPaymentGateway`만 안 걸려 있었다.

> 새 결함을 찾는 것보다 **같은 문제를 코드베이스가 몇 가지 방식으로 처리하는지 보는 것**이 싸다.
> 한쪽에 이미 정답이 있으면, 그 정답이 왜 다른 쪽에는 없는지만 물으면 된다.

그리고 이번 건은 **문서가 지식을 보존했지만 전파하지는 못한 사례**다. `KopisClientConfig`의
주석은 훌륭했고 정확했다. 그런데 그 지식이 결제 코드까지 가지 않았다. **규칙 ⑱은 그 주석을
기계가 강제하는 형태로 옮긴 것**이다 — 문서로 남긴 지식이 다음 코드에도 적용되게 하려면
결국 검사로 바꿔야 한다.
