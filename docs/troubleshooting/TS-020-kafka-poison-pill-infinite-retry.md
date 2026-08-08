# TS-020 · DLQ가 있는데 독성 메시지는 DLQ로 못 갔다 — 역직렬화 실패는 리스너에 도달하지 않는다

- 슬라이스: S08(이벤트/DLQ) · S09(배포)
- 날짜: 2026-08-09
- 유형: 정합성/가용성 결함(설정) — **조용히 처리가 멈춤**
- 관련: [[ADR-008]](Kafka 백본·DLQ), [[TS-019]](HPA), `global/config/KafkaConfig.java`
- 상태: 해결(설정 + 회귀 테스트)

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

`ErrorHandlingDeserializer`가 실패를 잡아 **null + 예외 헤더**로 바꿔 리스너 단계까지 전달한다.
그때부터는 `DefaultErrorHandler`의 관할이라 재시도 후 DLT로 넘어간다.

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
