# IMP-016 · Kafka 브로커 1대 장애 실증 — 계산이 아니라 실측으로

- 슬라이스: S09(EKS 배포) — 인프라
- 날짜: 2026-08-08
- 유형: 정성/정량(내결함성 실증)
- 관련: [[ADR-008]](Kafka 백본), [[ADR-010]](아웃박스), [[TS-016]](Kafka 기동 의존), `k8s/kafka/kafka.yaml`
- 상태: **부분 검증** — Kafka 레벨은 확인, 앱 경로는 미검증(§5)

## 1. 배경 — 주석에 계산만 있었다

`k8s/kafka/kafka.yaml`에 내결함성을 이렇게 적어 뒀다.

```
3대 정상 → ISR 3 → 쓰기 가능
1대 장애 → ISR 2 → min.insync=2 충족 → 쓰기 가능
2대 장애 → ISR 1 → 미충족 → acks=all 발행 실패
```

이건 **계산**이지 증거가 아니다. 초안에서는 "2대까지 견딘다"고 잘못 적었다가 리뷰에서
"정확히 1대"로 정정된 부분이기도 하다 — **그렇다면 실제로 그런지 봐야 한다.**

## 2. 기준선

앱이 자동 생성한 토픽인데 설정이 의도대로 들어갔다.

```
Topic: order-events   PartitionCount: 6   ReplicationFactor: 3   min.insync.replicas=2
  Partition 0  Leader: 2  Replicas: 2,0,1  Isr: 2,0,1
  Partition 1  Leader: 0  Replicas: 0,1,2  Isr: 0,1,2
  Partition 2  Leader: 1  Replicas: 1,2,0  Isr: 1,2,0
```

브로커 설정도 확인했다 — `min.insync.replicas=2` (STATIC_BROKER_CONFIG, 기본값 1을 덮음).

## 3. 실험

```bash
kubectl delete pod flowticket-dual-role-2 -n kafka --grace-period=0 --force
```

유예 없이 강제 종료했다. graceful shutdown이면 브로커가 리더십을 미리 넘기므로
**장애에 더 가까운 조건**을 만들기 위해서다.

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

**`min.insync=2`를 정확히 만족하는 경계**(ISR 2)에서 쓰기가 이어졌다. 주석의 계산이 맞았다.

**API Pod가 재시작되지 않은 것**도 중요하다 — [[TS-016]]에서 "브로커가 없으면 기동조차 못 한다"를
확인했는데, **1대 장애로는 부트스트랩 DNS가 여전히 풀려** 살아 있는 Pod가 영향을 받지 않았다.

## 5. ⚠️ 검증하지 못한 것 (중요)

### 앱 경로(`order.paid`)는 확인하지 못했다

Kafka를 타는 유일한 이벤트는 아웃박스가 발행하는 `order.paid`다. 그걸 발생시키려면
**결제를 완료해야 하는데**, 결제 게이트웨이가 Toss 결제창이라 curl로 자동화할 수 없었다.

`kafka-console-producer --acks=all`로 쓰기가 되는 것은 확인했지만 그건 **Kafka 레벨**이고,
"브로커 1대가 죽어도 **우리 앱의** 이벤트 발행이 계속된다"는 **아직 증거가 없다.**

> 좌석 선점(`seat.held`)으로 대신 확인하려 했으나 두 가지 이유로 무의미했다 —
> 큐 토큰이 만료돼 실패했고(`QUEUE_NOT_ADMITTED`), 애초에 `seat.held`는 **Redis pub/sub**을 타서
> Kafka와 무관하다.

### 2대 장애는 하지 않았다

계산상 `ISR 1 < min.insync 2`라 **acks=all 발행이 실패**해야 한다. 그리고 그때
[[TS-016]] §7의 위험(브로커 없으면 Pod 재기동 불가)이 실제로 드러날 것이다.
**의도적으로 하지 않았다** — 지금은 데모 환경이 살아 있어야 해서다.

### 그 밖에

- **1회 측정**이다. 리더 선출이 항상 이만큼 빠른지는 모른다.
- 종료~복구 사이의 **정확한 다운타임을 측정하지 않았다.** "쓰기가 됐다"까지다.
- 브로커가 **곧바로 재생성**됐다(StrimziPodSet). 장기 장애 상황은 재현하지 않았다.

## 6. 남는 질문

**아웃박스가 진짜 값을 하는 구간은 "브로커 전체 다운"이다.** ADR-010이 주장하는 "유실 0"은
발행이 실패해도 PENDING으로 남았다가 재시도된다는 것인데, **1대 장애에서는 발행이 아예
실패하지 않으므로 그 경로가 발동하지 않는다.**

즉 이 실험은 **"RF/min.insync 설정이 맞다"를 증명했지, "아웃박스가 유실을 막는다"를 증명하지 않았다.**
후자는 IMP-011에서 로컬로 실증했고(도달 0/10 → 유실 0), 클라우드 재현은 S10의 몫이다.
