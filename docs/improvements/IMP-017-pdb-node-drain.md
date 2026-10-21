# IMP-017 · 노드 드레인 중 무중단, 2,400건 5xx 0건 (PDB 발동)

- 슬라이스: S09(EKS 배포), 인프라
- 날짜: 2026-08-09
- 유형: 정량(가용성 실증)
- 관련: [[IMP-015]](롤링 배포 무중단), [[IMP-016]](브로커 페일오버), `k8s/base/api-pdb.yaml`
- 상태: 검증 완료(조건 §5)

## 1. 배경

PDB를 넣을 때 매니페스트 주석에 목적을 적어 두었다. 노드 드레인(오토스케일 축소·노드 교체·AZ 작업)
중에도 최소 용량을 지키는 것이다. 배포 중 용량을 다루는 롤링 배포의 `maxUnavailable`과는 다른 축으로,
이쪽은 노드 작업 중의 용량을 다룬다.

[[IMP-015]]에서 롤링 배포 무중단을 측정한 뒤, 선언해 둔 PDB를 실제 드레인으로 발동시켜 확인했다
([[IMP-015]] §7).

## 2. 실험

```
대상   https://flow-ticket.com/api/events/1678/seats
부하   병렬 4워커
행위   부하 중 kubectl drain <node> --ignore-daemonsets --delete-emptydir-data
```

드레인 대상 노드에는 api 파드 2개 + web 1개 + Kafka 브로커 1개가 올라가 있었다.
(HPA가 앞선 부하로 api를 5개까지 늘려 둔 상태였다.)

## 3. 결과

```
23:58:54  드레인 시작
  evicted: flowticket-api × 2, flowticket-web × 1,
           flowticket-dual-role-1(Kafka 브로커) × 1, coredns × 1
23:59:09  node drained  (15초)

총 요청  2,400
200      2,400  (100%)
5xx          0
```

노드 하나가 빠지는 동안 2,400건 모두 200이었다. Kafka 브로커까지 함께 축출됐는데도
API 응답에 영향이 없었다([[IMP-016]]의 1대 장애 결과와 일관된다).

드레인 후 파드는 남은 2개 노드로 재배치됐고, `uncordon`으로 노드를 되돌렸다.

## 4. 무엇이 이것을 지켰나

| 설정 | 역할 |
|---|---|
| `PodDisruptionBudget minAvailable: 2` | 5개 중 3개까지만 동시 축출 허용. 축출 요청을 API 서버가 거절/대기시킨다 |
| `maxUnavailable: 0` | 대체 파드가 Ready된 뒤에만 옛 파드 제거 |
| `topologySpreadConstraints` | 파드가 AZ에 흩어져 있어 노드 하나가 전부를 갖고 있지 않았다 |
| `preStop 5s` + `deregistration_delay 10s` | ALB가 타깃을 빼는 동안 진행 중 요청 드레이닝 |

PDB는 동시에 축출할 수 있는 파드 수만 통제한다. 축출되는 파드로 가던 요청은 마지막 두 설정
(preStop, 등록 해제 드레이닝)이 함께 있어야 보호된다.

## 5. 측정 조건

- 파드 5개, `minAvailable: 2`라 동시에 3개까지 축출할 수 있는 여유가 있었다.
  api 3파드 조건과 PDB 차단 장면, Cluster Autoscaler 조건은 §7(2026-08-26)에서 측정했다.
- 부하는 4워커, 1회 측정이다. 선착순 피크 부하는 S10 부하테스트에서 다룬다.

## 6. 다음 개선

- 드레인 중 응답 지연 변화는 따로 재지 않았다(5xx 여부만 확인). 드레인 구간만 떼어 지연을 보는 것이
  다음 측정 항목이다.

---

## 7. 재측정 (2026-08-26), Cluster Autoscaler 조건

PDB 차단 장면과 CA 조건을 이 재측정에서 확인했다([[IMP-021]]). 절차는
`scripts/resilience-test.sh --scenario drain`으로 고정해, 기존의 수동 `kubectl drain`을 코드로 옮기고
실행마다 같은 절차가 돌도록 했다(배경은 [[IMP-015]]·[[TS-035]]).

```
클러스터  EKS 1.35 · m6i.large × 3 · Cluster Autoscaler 있음
부하      클러스터 안 k6, 25 rps × 4분 (open model)
행위      파드가 가장 많은 노드를 drain
```

부하 생성기는 nodeAffinity로 드레인 대상 노드에서 제외했다. 측정기가 함께 축출되면 그 실패를
서비스 장애와 구분할 수 없기 때문이다.

### 결과

```
총 요청   6,000
non-2xx        0
p95/p99   24ms / 40ms
장애 전 기준선 0
```

### PDB 축출 차단

```
error when evicting pods/"flowticket-api-85ccf58f5-l44js" -n "flowticket"
  (will retry after 5s): Cannot evict pod as it would violate the pod's disruption budget.
evicting pod flowticket/flowticket-api-85ccf58f5-l44js   ← 재시도
pod/flowticket-api-85ccf58f5-l44js evicted
node/ip-10-0-102-100... drained
```

`flowticket-api minAvailable=2` 상태에서 드레인이 한 번 거부되고 재시도로 통과했다.
PDB가 실제로 축출을 제한하는 것을 로그로 확인했다.

### CA 용량 보충

§3에서는 CA가 없어 파드가 남은 2개 노드로 재배치됐다. 이번에는 CA가 노드를 추가했다.

```
drain T0  10:31:47Z
+30s      노드 3 → 4
10:32:52Z, 10:33:39Z   노드 2대 추가 → 최종 5대
```

추가된 2대는 모두 AZ-c 그룹에 붙었다(`balance-similar-node-groups` 활성 상태, [[IMP-021]] §6).

### 측정 조건

- 1회 측정, 부하 25 rps.
- p95/p99는 전체 구간 값이며, 드레인 구간만 떼어 보지 않았다(§6).
- 파드 수 조건이 §5(5개)와 다르다. 이번에는 api 3파드 상태라 `minAvailable=2`에 붙어 PDB 차단이 관측됐다.
