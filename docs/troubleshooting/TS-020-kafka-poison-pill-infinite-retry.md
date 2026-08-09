# TS-020 · DLQ가 있는데 독성 메시지는 DLQ로 못 갔다 — 역직렬화 실패는 리스너에 도달하지 않는다

- 슬라이스: S08(이벤트/DLQ) · S09(배포)
- 날짜: 2026-08-09
- 유형: 정합성/가용성 결함(설정) — **조용히 처리가 멈춤**
- 관련: [[ADR-008]](Kafka 백본·DLQ), [[TS-019]](HPA), `global/config/KafkaConfig.java`
- 상태: **해결** — CI 회귀 테스트 + **실클러스터 검증**(§6-2)

## 1. 어떻게 발견했나 — 내가 만든 사고였다

[[IMP-016]] 브로커 페일오버 실증에서 `acks=all` 쓰기가 되는지 보려고 **운영 토픽에 평문을
넣었다.**

```bash
echo 'failover-test-1' | kafka-console-producer.sh --topic order-events --producer-property acks=all
```

`order-events`는 `JsonDeserializer`로 소비되는데 **타입 헤더가 없는 평문**이었다.
**테스트용 별도 토픽을 썼어야 했다.** 이건 명백히 내 실수다.

그런데 그 실수가 **원래 있던 결함**을 드러냈다.

## 2. 증상 — 아무것도 죽지 않는데 처리가 멈춘다

관측 스택을 설치하려고 노드 자원을 보다가 이상한 것을 발견했다.

```
kubectl top nodes
  ip-10-0-72-253   cpu=102%      ← 외부 부하는 이미 껐는데

kubectl top pods
  flowticket-api-...-mzk2j   cpu=711m   ← 한 파드만 계속 태우는 중
```

로그를 보니 같은 예외가 무한 반복되고 있었다.

```
Caused by: java.lang.IllegalStateException: No type information in headers and no default type provided
  at JsonDeserializer.deserialize(JsonDeserializer.java:582)
  at CompletedFetch.parseRecord(...)
  at KafkaMessageListenerContainer$ListenerConsumer.pollConsumer(...)
```

**그런데 아무것도 실패로 보이지 않았다.**

| | 상태 |
|---|---|
| 파드 | `Running`, `restarts=0` |
| readiness | `UP` — Kafka를 일부러 뺐으므로(C-1) |
| API 응답 | 200 |
| **실제 이벤트 처리** | **완전히 멈춤** |
| **부작용** | CPU 폭주 → **HPA가 부하로 오해해 스케일업** |

## 3. 근본 원인 — 오류 처리기가 개입할 수 없는 단계

`KafkaConfig`에는 DLQ 경로가 제대로 있었다.

```java
@Bean
public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> template) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
    return new DefaultErrorHandler(recoverer, new FixedBackOff(300L, 2L));
}
```

**문제는 이 핸들러가 다루는 범위다.**

```
poll() → 역직렬화 → 리스너 호출 → (예외) → DefaultErrorHandler → 재시도 → DLT
          ↑
          여기서 터지면 리스너에 도달조차 못 한다 → 핸들러 밖 → 오프셋 커밋 불가 → 무한 재시도
```

`value-deserializer`가 `JsonDeserializer` **직접**이었다. 역직렬화 실패는 `poll()` 단계에서
발생하므로 `DefaultErrorHandler`의 관할이 아니고, 컨테이너는 오프셋을 넘기지 못해
**같은 레코드를 영원히 다시 읽는다.**

## 4. ADR-008의 주장과 어긋난다

ADR-008은 "소비 실패 → 재시도 → DLQ 적재"를 말한다. 그런데 **재시도로 절대 해결되지 않는
유형**(독성 메시지)이 하필 그 경로를 못 탄다. **가장 DLQ가 필요한 실패가 DLQ에 못 간 것이다.**

## 5. 해결

```yaml
value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
properties:
  spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
  spring.json.trusted.packages: "com.flowticket.*"
```

`ErrorHandlingDeserializer`가 실패를 잡아 **null + `DeserializationException` 헤더**를 가진
레코드를 만든다. 그러면 **컨테이너가 리스너를 호출하지 않고** 바로 에러 핸들러를 부르므로,
`DefaultErrorHandler`의 관할이 되어 DLT로 넘어간다.

```
ErrorHandlingDeserializer → null + 예외 헤더 레코드
  → 컨테이너가 리스너를 호출하지 않음
  → DefaultErrorHandler 호출 → 재시도 소진 → DLT
```

### ⚠️ 이것만으로는 절반이다 — DLT 발행·소비도 바꿔야 한다

`DeadLetterPublishingRecoverer`는 역직렬화 실패일 때 **원본 `byte[]`를 그대로** 싣는다.
그런데 프로듀서가 `JsonSerializer` 하나뿐이면 그 바이트가 **base64 JSON 문자열**로 나가고,
DLT 소비 쪽에서 다시 역직렬화에 실패한다 — **독성 메시지가 DLT로 이사할 뿐**이고
DLT에는 다시 보낼 곳이 없어 거기서 무한 재시도가 된다.

그래서 두 가지를 함께 바꿨다.

```java
// ① 프로듀서: 타입에 따라 직렬화기를 나눈다
new DelegatingByTypeSerializer(Map.of(
        byte[].class, new ByteArraySerializer(),   // 역직렬화 실패분(원본 바이트)
        Object.class, new JsonSerializer<>()),     // 정상 이벤트
        true);

// ② DLT 소비: 타입으로 받지 않고 불투명한 바이트로 기록만 한다
@KafkaListener(topics = ORDER_EVENTS_DLT, containerFactory = "dltListenerContainerFactory")
public void onDeadLetter(byte[] body, ...)
```

DLT는 **정상 이벤트의 JSON과 원본 바이트가 섞여 들어오는 곳**이다. 한 타입으로 받으려 하는
순간 같은 문제가 반복된다. 판단(재시도/폐기)은 사람이 admin API로 한다(ADR-008).

**즉시 조치**로는 오염된 토픽을 삭제해 무한 재시도를 끊었다(앱이 자동 재생성).
CPU 711m → 17m, 노드 100% → 8%, 에러 0건으로 회복됐다.

## 6. 재발 방지 — 기존 DLQ 테스트가 이 경로를 못 잡았다

`DlqIntegrationTest`에 이미 3개의 DLQ 테스트가 있었다. 그런데 전부 이런 형태였다.

```java
kafkaTemplate.send(ORDER_EVENTS_TOPIC, "1", OrderEvent.of("order.paid", 111L));  // 정상 객체
doThrow(new RuntimeException("boom")).when(orderSse).broadcast(...);              // 리스너에서 실패
```

**역직렬화에 성공한 뒤 리스너에서 던지는 경우**만 덮고 있었다. 그래서 이 결함을 통과시켰다.

추가한 테스트는 **원시 프로듀서로 타입 헤더 없는 평문을 직접 넣는다**.

```java
raw.send(new ProducerRecord<>(ORDER_EVENTS_TOPIC, "poison", "not-json-at-all")).get();
// 무한 재시도에 빠지면 DLQ 행이 영영 생기지 않아 타임아웃된다
```

## 6-2. 실환경 검증 (2026-08-09)

CI(단일 브로커 Testcontainers)는 통과했지만, **실클러스터(브로커 3대·RF 3)에서는 확인하지
않은 상태**였다. 어제의 사고를 **의도적인 실험으로 다시 만들어** 확인했다.

```bash
echo 'poison-1786254749' | kafka-console-producer.sh --bootstrap-server localhost:9092 --topic order-events
```

| 관측 | 어제(수정 전) | 오늘(수정 후) |
|---|---|---|
| DLT 내용 | (도달 못 함) | **`poison-1786254749`** — 원본 평문 그대로 |
| `No type information` 반복 | 초당 수십 회, 멈추지 않음 | **0건** (4개 파드 전부) |
| API 파드 CPU | **711m**, 노드 100% | 16~186m (평시) |
| HPA | 부하로 오해해 스케일업 | 반응 없음 |

**세 가지가 동시에 확인됐다.**

1. **`ErrorHandlingDeserializer`가 동작한다** — 무한 재시도가 사라졌다.
2. **직렬화기 구성이 맞다** — DLT에 값이 **평문 그대로** 들어갔다.
   `JsonSerializer` 하나였다면 base64 JSON 문자열(`"cG9pc29uLi4u"`)로 보였을 것이다.
   리뷰가 지적했던 지점이 실환경에서 검증된 셈이다.
3. **DLT 소비도 깨지지 않는다** — `byte[]`로 받으므로 DLT 쪽에서도 재시도 루프가 없다.

> 어제는 이 메시지 **하나**가 노드 CPU를 100%까지 태우고 HPA를 오작동시켰다.
> 지금은 조용히 DLT로 넘어가고 끝난다.

### DB 행까지 직접 확인했다

DLT 소비 이후 `dlq_messages`에 실제로 적재됐는지도 봤다(RDS는 프라이빗 서브넷이라
클러스터 안에 socat 터널 파드를 띄우고 `kubectl port-forward`로 접속).

```sql
SELECT id, topic, left(payload,40) AS payload, left(error_message,60) AS err, status, created_at
FROM dlq_messages ORDER BY id DESC LIMIT 5;
```
```
1 | order-events | poison-1786254749 | failed to deserialize | PENDING | 2026-08-09 05:52:33
```

**세 필드가 각각 다른 것을 증명한다.**

| 필드 | 값 | 의미 |
|---|---|---|
| `payload` | `poison-1786254749` | **평문 그대로** — `byte[]` 직렬화가 맞다는 직접 증거. `JsonSerializer` 하나였다면 base64 문자열이었을 것이다 |
| `error_message` | `failed to deserialize` | **역직렬화 경로**로 들어왔다. 리스너 실패(`boom`)와 구분된다 |
| `status` | `PENDING` | 정상 DLQ 상태 — 재시도/폐기 판단 대기 |

이로써 `poison → ErrorHandlingDeserializer → DefaultErrorHandler → DLT(byte[]) → DlqConsumer
→ dlq_messages` **전 구간이 실환경에서 확인됐다.**

## 7. 교훈

1. **"오류 처리기가 있다"와 "그 오류를 처리한다"는 다르다.** 핸들러의 관할 범위를 봐야 한다 —
   여기서는 파이프라인의 어느 단계에서 터지느냐가 갈랐다.
2. **테스트가 있어도 경로가 다르면 못 잡는다.** DLQ 테스트가 3개나 있었는데 전부 같은 경로만 밟았다.
   [[TS-013]](집계 불변식)과 같은 구조다 — 있는 테스트의 **바깥**이 뚫린다.
3. **가장 위험한 실패는 아무것도 실패로 보이지 않는 것이다.** 파드 Running, readiness UP, 응답 200인데
   처리는 멈춰 있었다. [[TS-017]]·[[TS-018]]·[[TS-019]]에 이어 네 번째 "조용한" 결함이다.
4. **관측 부재가 발견을 늦췄다.** `kubectl top`을 우연히 본 덕에 찾았다.
   Prometheus/Grafana가 있었다면 consumer lag 그래프로 즉시 보였을 것이다 — Phase 7의 근거가 하나 더 생겼다.
5. **테스트는 격리된 리소스에서 하라.** 운영 토픽에 테스트 메시지를 넣은 것이 사고의 출발이었다.
