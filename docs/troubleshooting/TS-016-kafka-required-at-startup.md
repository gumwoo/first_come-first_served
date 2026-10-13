# TS-016 · readiness에서 Kafka를 뺐는데도 Pod가 못 떴다 — 기동 의존과 트래픽 의존은 다르다

- 슬라이스: S09(EKS 배포) · 횡단
- 날짜: 2026-08-08
- 유형: 배포/기동 결함(설정) · 설계 전제 오류
- 관련: [[ADR-008]](Kafka 이벤트 백본), [[ADR-010]](아웃박스),
  `docs/deployment/app-changes-for-k8s-kafka.md` C-1
- 상태: 해결(Strimzi 설치) → **구조적 해결(2026-10-13, §9)** — 리스너를 기동 뒤 재시도로 켠다

## 1. 증상

EKS에 처음 배포했다. **web은 뜨는데 api만** `CrashLoopBackOff`.

```
flowticket-api-58669fd9d5-9zwmd  0/1  CrashLoopBackOff  restarts=4
flowticket-api-58669fd9d5-bqrqv  0/1  CrashLoopBackOff  restarts=4
flowticket-api-58669fd9d5-xrxdd  0/1  CrashLoopBackOff  restarts=4
flowticket-web-68dfdc766c-g2hph  1/1  Running           restarts=0
```

## 2. 예상과 달랐던 점

**Kafka(Strimzi)를 아직 설치하지 않은 상태였고, 그래도 뜰 것이라 예상했다.** 근거는 두 가지였다.

```yaml
# application.yml — readiness에 Kafka를 일부러 뺐다(C-1)
readiness:
  include: readinessState, db, redis
# Kafka는 의도적으로 제외: 브로커 장애는 알림 지연일 뿐이고 아웃박스가 유실을 막는다
```
```yaml
spring.kafka.consumer.properties.missing-topics-fatal: false
```

**둘 다 이 상황에 해당하지 않았다.**

## 3. 조사 — 로그가 바로 답을 줬다

```
WARN  ClientUtils : Couldn't resolve server flowticket-kafka-bootstrap.kafka.svc:9092
                    from bootstrap.servers as DNS resolution failed
ERROR KafkaAdmin  : Could not create admin
      Caused by: ConfigException: No resolvable bootstrap urls given in bootstrap.servers
WARN  ApplicationContext : Failed to start bean 'internalKafkaListenerEndpointRegistry'
ERROR SpringApplication  : Application run failed
      Caused by: KafkaException: Failed to construct kafka consumer
```

→ `KafkaListenerEndpointRegistry`가 **스프링 라이프사이클 빈**이라, 기동에 실패하면
**컨텍스트 refresh가 취소되고 프로세스가 exit 1**로 죽는다. probe가 개입할 여지조차 없다.

## 4. 근본 원인 — 두 가지를 혼동했다

| | 무엇을 다루나 | 우리가 해둔 것 |
|---|---|---|
| **readiness 제외** | **이미 뜬** Pod가 브로커 장애로 트래픽에서 빠지지 않게 | ✅ 의도대로 |
| **`missing-topics-fatal: false`** | 브로커는 있는데 **토픽이 없을 때** | ✅ 하지만 해당 없음 |
| **기동 시 의존** | 브로커 주소가 **DNS로 풀리지 않을 때** | ❌ **아무 대비 없음** |

`missing-topics-fatal`은 이름 그대로 **토픽**에 대한 옵션이다. 브로커 주소 자체가 해석되지
않으면 컨슈머를 만드는 단계에서 실패하므로 이 옵션이 관여할 지점이 아니다.

> **"장애를 견딘다"와 "없이도 시작한다"는 다른 문제다.** 앞의 설정들은 전자만 다루고 있었다.

## 5. 해결

Strimzi Operator + Kafka CR을 설치했다(배포 계획 Phase 4). 앱이 기대하는 주소가
`flowticket-kafka-bootstrap.kafka.svc:9092`이므로 **클러스터 이름 `flowticket`, 네임스페이스
`kafka`** 로 맞춰야 한다 — 이름이 어긋나면 증상이 똑같이 재현된다.

```
Kafka Ready=True  브로커 3/3
kubectl rollout restart deployment/flowticket-api
→ api Ready 3/3, restarts=0, {"status":"UP"}
→ 토픽 자동 생성 확인: order-events, order-events.DLT, __consumer_offsets
```

**StorageClass 이름도 계약이다.** 이 클러스터에는 **기본 StorageClass가 없다**
(`gp2`는 있지만 `is-default-class` 표시가 없다). `class: gp2`를 명시했기 때문에 붙었고,
생략했다면 PVC가 `Pending`에 머물러 브로커가 뜨지 않았을 것이다 — 증상은 이 문서의 것과
비슷하지만 원인은 전혀 다르다. gp2는 저장소가 만드는 것이 아니라 EKS가 기본 제공하는 것이라
클러스터를 새로 만들 때마다 실제 이름을 확인해야 한다(gp3인 환경도 흔하다).

KRaft 모드(ZooKeeper 없음)라 `KafkaNodePool` + `Kafka` CR 조합을 쓴다. 노드가 3대뿐이라
controller/broker를 겸하게 했다. `deleteClaim: true` — 데모는 매번 새로 만들므로 PVC를 남기면
destroy 후 EBS 요금이 붙는다.

## 6. 재발 방지

- **매니페스트에 근거를 박았다**: `k8s/kafka/kafka.yaml` 상단에 "이름이 어긋나면 api가 기동
  자체에 실패한다(TS-016)"를 적었다. 이름은 설정이 아니라 **계약**이다.
- **배포 순서를 문서에 고정**: Kafka는 앱보다 **먼저** 떠야 한다. "readiness에서 뺐으니 나중에
  붙여도 된다"는 판단이 틀렸음이 확인됐다.

## 7. 남은 구조적 문제 (→ 2026-10-13 해결, §9)

**이번 수정은 "브로커를 먼저 띄운다"이지, "브로커 없이도 뜬다"가 아니다.** 그래서 다음 시나리오는
여전히 열려 있다.

> 브로커가 전부 죽은 상태에서 **api Pod가 재시작되면 다시 올라오지 못한다.**
> 살아 있던 Pod는 버티지만(readiness 제외 덕분), 롤링 배포·노드 교체·OOM 재시작이 겹치면
> **브로커 장애가 API 전면 장애로 번진다.**

즉 **"브로커 장애를 견딘다"는 설계 목표가 절반만 달성된 상태**다. 닫으려면
`spring.kafka.listener.auto-startup=false` + 런타임 기동 같은 변경이 필요하고, 그러면
"언제 리스너를 켤 것인가"라는 새 문제가 생긴다. **이번 범위에서는 하지 않았고, 열려 있음을 남긴다.**

## 8. 교훈

1. **probe 설정은 "이미 뜬 뒤"의 이야기다.** 기동 실패는 probe 이전에 일어난다 —
   readiness에서 뺐다는 사실이 기동 의존을 없애주지 않는다.
2. **옵션 이름을 근거로 삼을 때는 그 옵션이 다루는 대상을 확인해야 한다.**
   `missing-topics-fatal`은 토픽에 대한 것이지 브로커 주소에 대한 것이 아니다.
3. **리뷰가 이걸 미리 경고했다.** *"'api Pod는 뜬다'는 확정적으로 쓰지 말고, 실제 기동 여부는
   Kafka listener/KafkaAdmin 초기화 설정 전체와 배포 환경에서 확인해야 한다."*
   확인하지 않고 단정했고, 배포에서 그대로 드러났다.

## 9. 구조적 해결 (2026-10-13)

§7의 "`auto-startup=false` + 런타임 기동"을 했다. "언제 켤 것인가"의 답: **기동 직후 한 번, 실패하면 켜질 때까지 주기적으로.**

- 리스너 컨테이너 두 팩토리(기본·DLT) 모두 `autoStartup=false`. `KafkaListenerStarter`가 `ApplicationStartedEvent`(readiness 이전)에 켠다 —
  브로커가 있으면 예전처럼 트래픽 전에 소비가 시작된다.
- 실패하면 앱은 그대로 두고 `kafka.listener.start-retry-interval`(기본 10초)마다 다시 켠다. 종료(`ContextClosedEvent`)가 오면 재시도를 멈춘다.
- **함정**: `ConcurrentMessageListenerContainer`는 자식 컨슈머를 만들기 **전에** `running=true`로 둔다(spring-kafka 3.2.4 `doStart`).
  생성이 실패해도 실행 중으로 남아 다음 `start()`가 예외도 없이 아무것도 하지 않는다 — 재시도가 조용히 무력화된다. 실패하면 `stop()`으로 되돌린다.
- `KafkaAdmin`(토픽 생성)은 원래 컨텍스트 초기화 중 실패를 로그로만 남긴다(`spring.kafka.admin.fail-fast` 기본 false) — 기동을 막던 것은 리스너뿐이었다.
  늦게 붙은 브로커에는 앱이 토픽을 만들지 않는다. 운영 토픽의 권위는 Strimzi `KafkaTopic` 쪽이다(`KafkaConfig` 주석).
- §7 인용의 "브로커가 전부 죽은 상태"는 정확한 실패 조건이 아니었다 — 실패는 브로커 **주소의 DNS 해석 실패**에서 났다(§3 로그).
  bootstrap Service(ClusterIP)가 남아 있으면 브로커가 죽어도 이름은 풀려 컨슈머 생성은 성공했을 것이다(추론 — 시험하지 않음, IMP-016 §5).
- 리스너가 꺼진 동안: 발행은 아웃박스가 PENDING으로 보존하고(ADR-010), 켜지면 그룹 오프셋(신규 그룹은 `earliest`)부터 소비한다.
  readiness는 원래 Kafka를 보지 않는다 — 트래픽은 계속 받는다.

검증:
- 단위(`KafkaListenerStarterTest`, 로컬 javac + JUnit 실행 5/5, 3회 반복): 실패가 예외로 번지지 않고 되돌린 뒤 **실제 재시도 스레드**가 다시 켬·켜진 뒤 더 시도하지 않음 /
  종료 뒤 켜지 않음 / 브로커가 있으면 한 번에 켜고 재시도 없음 / **실제 `ConcurrentMessageListenerContainer`**(해석되지 않는 주소)로
  "실패 뒤 running으로 남아 다음 start()가 무시된다"와 "되돌리면 다시 컨슈머를 만든다"를 확인.
  변이: 되돌리기를 빼면 2건, 재시도 예약을 빼면 1건 실패.
- 통합(`KafkaUnresolvableBootstrapIntegrationTest`, CI Testcontainers): bootstrap을 해석되지 않는 주소(`.invalid`)로 두고 컨텍스트가 뜨는지,
  리스너는 꺼져 있고 readiness는 UP인지. 예전 코드였다면 컨텍스트가 뜨지 않아 실패했을 테스트다.
- 확인하지 못한 것: 실제 클러스터에서 Kafka 없이 api를 띄운 뒤 Strimzi를 올려 리스너가 재시도로 켜지는 것(클러스터 세션에서 확인할 항목).
