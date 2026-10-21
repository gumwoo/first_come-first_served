# IMP-021 · Cluster Autoscaler 실증, Pending 4개에서 노드 3→4, 축소까지 확인

- 슬라이스: S09(EKS 배포), 인프라
- 날짜: 2026-08-26
- 유형: 정량(오토스케일 실증)
- 관련: [[ADR-012]] §4(CA 결정), [[IMP-016]]·[[IMP-017]](같은 세션에서 CA 조건으로 재측정),
  `scripts/node-autoscale-test.sh`, `k8s/cluster-autoscaler/values.yaml`
- 상태: 검증 완료(조건 §5) — 확장·축소 각 1회

## 1. 배경

이 프로젝트는 HPA는 있었지만 노드는 고정 3대였다. HPA 상한을 노드 예산 안에 맞춰
(`api 7×300 + web 4×300 + 기타 1,650 = 4,950m / allocatable 5,790m`) Pending을 피하는
구조였다. Pod는 늘어나지만 노드는 늘어나지 않았다.

ADR-012 §4는 CA를 두기로 결정했고, IRSA 역할·ASG 태그·`ignore_changes`까지 준비돼 있었으나
컨트롤러는 배포되지 않은 상태였다. 2026-08-25에 이를 확인해 미도입으로 정리했다가(#275),
설치와 실측 검증을 함께 하는 조건으로 도입했다(#276).

이 문서가 그 측정이다.

## 2. 측정 방법

```
클러스터  EKS 1.35 · m6i.large × 3 (AZ당 min 1 / max 3 = 최대 9)
CA        차트 9.59.0 (app 1.35.0) · scale-down-unneeded-time 기본 10분
부하      클러스터 안 k6, 400 rps, 8분 (read-load-rate.js, open model)
행위      HPA api maxReplicas를 7 → 12로 일시적으로 올려 노드 예산을 일부러 넘긴다
기록      10초 간격으로 노드 수 / Ready / api 파드 수 / Pending
```

상한을 올리는 이유: 평시 구성에서는 HPA가 상한까지 늘려도 예산 안에 들어가므로 CA가 발동하지
않는다. CA 동작을 확인하려면 Pending이 실제로 생기고, 노드가 붙어 해소되는 과정을 봐야 한다.
`12 × 300m = 3,600m`로 예산을 넘긴다. 평시 구성과 다른 조건이며 §5에 정리했다.

## 3. 결과: 확장

```
경과      노드  Ready  api파드  Pending
T0+0s      3     3       3        1
T0+41s     3     3       7        0
T0+55s     3     3      12        4     ← HPA 상한 도달, 예산 초과 → Pending 발생
T0+96s     3     3      12        3
T0+110s    4     3      12        3     ← CA가 노드를 붙였다
T0+128s    4     4      12        0     ← Ready 완료, Pending 해소
```

| 항목 | 값 |
|---|---|
| 최대 Pending | **4개** |
| 노드 | **3 → 4** |
| 노드 증가까지 | **110초** |
| Pending 해소까지 | 128초(노드 Ready 시점) |

110초는 ADR-012 §한계가 적어 둔 "EC2 부팅 + 클러스터 조인, 통상 분 단위"의 실측값이다.
HPA 반응(초 단위)과 자릿수가 다르다는 서술을 숫자로 확인했다.

## 4. 결과: 축소

부하를 제거하고 HPA 상한을 7로 되돌린 뒤 관찰했다.

```
T1+0s    노드 4
...
T1+718s  노드 4
T1+739s  노드 3     ← 축소
```

739초(12분 19초). CA 기본 `scale-down-unneeded-time`이 10분이므로, 그 위에 판정·드레인
시간이 얹힌 값이다. 이 값을 줄이면 측정이 빨라지지만 기본 동작을 재기 위해 기본값을 유지했다.

### 축소와 Kafka 브로커

ADR-012 §한계가 경고한 것은 *"CA scale-down이 Kafka 브로커를 옮길 수 있다"*였다.
축소 후 브로커 3대가 모두 `Running`이었고 PDB(`flowticket-kafka minAvailable=2`)도 유지됐다.

`flowticket-dual-role-0`에 재시작 1회가 있었고, 시간축상 CA 축소와 무관하다.

```
liveness 실패  10:15:04Z
컨테이너 종료  10:15:33Z   (reason=Completed, exitCode=0)
CA 확장       10:14:43Z
CA 축소 삭제  10:26:37Z   ← 11분 뒤
```

재시작이 축소보다 11분 먼저 일어났다. ADR-012 §한계가 경고한 *"CA scale-down이 브로커를
옮긴다"* 경로는 이번 측정에서 발생하지 않았다.

재시작의 직접 원인은 liveness 프로브다.

```
10:15:04Z  Warning  Unhealthy  Liveness probe failed: command timed out:
                               "/opt/kafka/kafka_liveness.sh" timed out after 5s
10:15:04Z  Normal   Killing    Container kafka failed liveness probe, will be restarted
```

kubelet이 명시적으로 `will be restarted`를 남겼다. 같은 세션의 사용자 요청에는 영향이
없었다([[IMP-016]] §7에서 6,000건 0 실패).

## 5. 측정 조건

- HPA `api maxReplicas`를 7 → 12로 일시적으로 올린 상태다. 평시(7)에서는 예산 안에 들어가
  CA가 발동하지 않는다. 스크립트가 끝나며 7로 되돌린다.
- 확장·축소 각 1회 측정이다.
- 노드 상한은 AZ당 3(총 9)이다. `loadtest.tfvars` 프로파일이며 기본값(AZ당 2)과 다르다.
- 부하 400 rps는 노드 예산을 넘기기 위한 값이다. 용량 한계(knee)는 [[TS-034]]에서 따로 다룬다.
- 측정 범위는 노드가 붙고 빠지는 동작까지다. 확장 후 처리량 변화는 이 측정의 범위 밖이다.

## 6. 부수 관측: 드레인 시 CA 반응

같은 세션의 [[IMP-017]] 재측정(노드 드레인) 중에도 CA가 반응했다.

```
drain T0    10:31:47Z
+30s        노드 3 → 4
이후         10:32:52Z, 10:33:39Z에 노드 2대가 추가돼 최종 5대
```

추가된 두 대는 모두 AZ-c 노드그룹에 붙었다(`cloudProviderTarget: 2a=1 / 2b=1 / 2c=3`,
`balance-similar-node-groups: true` 설정 상태). 노드 증가는 드레인 후 30초 안에 관측됐다
(확장 테스트에서는 110초).

## 7. 다음 개선

- 반복 측정: 확장·축소 각 1회를 늘린다.
- AZ 편중: `balance-similar-node-groups` 설정에서 한쪽 그룹에 노드가 몰린 원인 분석.
- 노드 증가 시간의 분산: 110초와 30초의 차이 분석.
- 브로커 liveness 타임아웃: 그 시점의 노드 CPU·디스크 IO를 함께 기록해 자원 경합 여부를 가린다.
- 오버프로비저닝: ADR-012 §한계가 개선 항목으로 둔 낮은 priority pause Pod. 노드 기동 110초
  동안 Pending이 유지되는 구간을 줄이는 방법이다.
- knee 재측정([[TS-034]]): knee는 고정 용량의 한계를 재므로, CA를 끈 별도 세션에서 측정한다.
