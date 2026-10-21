# IMP-016 · Kafka 브로커 1대 장애 실증

> Kafka 제거([[ADR-028]]) 전 기록이다. 브로커·컨슈머·DLQ 서술은 당시 구조 기준이며, 본문은 보존한다.

- 슬라이스: S09(EKS 배포), 인프라
- 날짜: 2026-08-08
- 유형: 정성/정량(내결함성 실증)
- 관련: [[ADR-008]](Kafka 백본), [[ADR-010]](아웃박스), [[TS-016]](Kafka 기동 의존), `k8s/kafka/kafka.yaml`
- 상태: **부분 검증** — Kafka 레벨은 확인, 앱 경로는 미검증(§5). CA 조건 재측정은 §7(2026-08-26)

## 1. 배경

`k8s/kafka/kafka.yaml` 주석에 내결함성을 계산으로 적어 두었다.

```
3대 정상 → ISR 3 → 쓰기 가능
1대 장애 → ISR 2 → min.insync=2 충족 → 쓰기 가능
2대 장애 → ISR 1 → 미충족 → acks=all 발행 실패
```

견딜 수 있는 장애는 정확히 1대다. 이 계산이 실제 클러스터에서 성립하는지 실측으로 확인했다.

## 2. 기준선

앱이 자동 생성한 토픽에 설정이 의도대로 들어갔다.

```
Topic: order-events   PartitionCount: 6   ReplicationFactor: 3   min.insync.replicas=2
  Partition 0  Leader: 2  Replicas: 2,0,1  Isr: 2,0,1
  Partition 1  Leader: 0  Replicas: 0,1,2  Isr: 0,1,2
  Partition 2  Leader: 1  Replicas: 1,2,0  Isr: 1,2,0
```

브로커 설정도 확인했다. `min.insync.replicas=2` (STATIC_BROKER_CONFIG, 기본값 1을 덮음).

## 3. 실험

```bash
kubectl delete pod flowticket-dual-role-2 -n kafka --grace-period=0 --force
```

유예 없이 강제 종료했다. graceful shutdown이면 브로커가 리더십을 미리 넘기므로,
장애에 가까운 조건을 만들기 위해 강제 종료를 택했다.

## 4. 결과

```
[종료 직후]
  ISR 3 → 2                    Isr: 0,1
  Partition 0 Leader 2 → 0     자동 리더 선출
  acks=all 쓰기                성공(에러 없음)
  API 파드                      3/3 Running, restarts=0
  API 응답                      200

[복구 후]
  브로커 3/3 Ready
  ISR 3개 전부 복원             Isr: 0,1,2
  under-replicated 파티션        0
```

`min.insync=2`를 정확히 만족하는 경계(ISR 2)에서 쓰기가 이어졌다. 주석의 계산과 일치한다.

API Pod도 재시작되지 않았다. [[TS-016]]에서 브로커가 없으면 api가 기동하지 못하는 것을 확인했는데,
1대 장애에서는 부트스트랩 DNS가 계속 풀려 살아 있는 Pod가 영향을 받지 않았다.
(2026-10-13: TS-016 §7 해소. 리스너를 기동 뒤 재시도로 켜, DNS가 안 풀려도 api는 기동한다.)

## 5. 검증 범위

### 앱 경로(`order.paid`)

Kafka를 타는 이벤트는 아웃박스가 발행하는 `order.paid`다. 이를 발생시키려면 결제를 완료해야 하는데,
결제가 Toss 결제창을 거쳐 curl로 자동화할 수 없다. 그래서 이번 실험은 `kafka-console-producer --acks=all`로
Kafka 레벨의 쓰기를 확인했고, 앱 이벤트 발행 경로는 범위에 포함하지 않았다.

### 2대 장애

계산상 `ISR 1 < min.insync 2`라 acks=all 발행이 실패해야 한다. 데모 환경을 유지해야 해서
2대 장애는 실행하지 않았다. 참고로 [[TS-016]]의 기동 실패 조건은 브로커 주소의 DNS 해석 실패이며,
2026-10-13 이후 이 경로도 기동을 막지 않는다(TS-016 §9).

### 측정 조건

- 1회 측정이다.
- 확인 항목은 쓰기 성공 여부이며, 종료~복구 사이 다운타임은 따로 재지 않았다.
- 브로커는 StrimziPodSet이 곧바로 재생성했다.

## 6. 아웃박스 경로와의 관계

아웃박스가 동작하는 구간은 브로커 전체 다운이다. ADR-010의 "유실 0"은 발행이 실패해도 PENDING으로
남았다가 재시도된다는 것이고, 1대 장애에서는 발행이 실패하지 않으므로 이 경로가 발동하지 않는다.

이 실험이 확인한 것은 RF/min.insync 설정이다. 아웃박스의 유실 방지는 IMP-011에서 로컬로
실증했고(도달 0/10 → 유실 0), 클라우드 재현은 S10에서 다룬다.

---

## 7. 재측정 (2026-08-26), Cluster Autoscaler 조건

§1~§6은 Cluster Autoscaler가 없는 상태에서 잰 값이다. 2026-08-26에 CA를 도입하면서([[IMP-021]])
전제가 바뀌었으므로 같은 실험을 다시 했다. ADR-012 §한계가 *"CA scale-down이 Kafka 브로커를 옮길 수
있다"*고 경고했기 때문이다.

절차는 `scripts/resilience-test.sh --scenario failover`로 고정했다. §3의
`kubectl delete pod --grace-period=0 --force`를 코드로 옮겨, 실행마다 같은 절차가 돌도록 했다([[TS-035]]).

```
클러스터  EKS 1.35 · m6i.large · Cluster Autoscaler 있음 · 노드 5대
부하      클러스터 안 k6, 25 rps × 4분 (open model)
행위      flowticket-dual-role-0 을 --grace-period=0 --force 로 강제 종료
```

### 결과

```
T0        10:37:43Z  브로커 강제 종료
+10s      준비된 브로커 2      ← 1대 빠짐
+30s      준비된 브로커 3      ← 복귀 완료
총 요청   6,000
non-2xx        0
p95/p99   13ms / 22ms
장애 전 기준선 0
```

브로커 1대가 강제 종료돼도 사용자 요청은 6,000건 전량 2xx였고, 30초 안에 3대로 복귀했다.
EBS(PVC)가 AZ에 종속돼 있어 대체 노드가 다른 AZ에 뜨면 복구가 실패할 수 있는데
(ADR-012 §노드그룹 분리의 근거), 이번에는 그 문제가 발생하지 않았다.

### 범위

부하는 조회 경로(`/events/{id}/seats`)이고 `order.paid`는 발생시키지 않았다. 따라서 §5의 앱 경로
범위와 §6의 아웃박스 경로 구분은 그대로 적용된다. 이 재측정이 추가로 확인한 것은 CA가 있는 상태에서도
브로커 1대 장애 시 사용자 요청이 실패하지 않는다는 점이다.

### 측정 조건

- 1회 측정, 25 rps.
- 브로커를 직접 종료한 실험이라, ADR-012가 경고한 "축소가 브로커를 옮기는" 경로와는 다르다.
  같은 세션의 [[IMP-021]] §4에서 축소 시 브로커가 유지되는 것을 관찰했고, 축소와 부하를 동시에 건
  조건은 이 측정에 포함하지 않았다.
