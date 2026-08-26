# IMP-017 · 노드 드레인 중 무중단 — 2,400건 5xx 0건 (PDB 발동)

- 슬라이스: S09(EKS 배포) — 인프라
- 날짜: 2026-08-09
- 유형: 정량(가용성 실증)
- 관련: [[IMP-015]](롤링 배포 무중단), [[IMP-016]](브로커 페일오버), `k8s/base/api-pdb.yaml`
- 상태: 검증 완료(조건 §5)

## 1. 배경 — 롤링과 다른 축이다

PDB를 넣을 때 매니페스트에 이렇게 적어 뒀다.

> "노드 드레인(오토스케일 축소·노드 교체·AZ 작업) 중에도 최소 용량을 지킨다.
> 롤링 배포의 `maxUnavailable`과는 다른 축이다 — 그건 '배포 중', 이건 '노드 작업 중'."

[[IMP-015]]에서 롤링 배포 무중단은 확인했지만, **PDB는 선언만 하고 발동시켜 본 적이 없었다.**
그 문서 §7에도 "아직 발동을 확인하지 못한 것"으로 남겨 두었다.

## 2. 실험

```
대상   https://flow-ticket.com/api/events/1678/seats
부하   병렬 4워커
행위   부하 중 kubectl drain <node> --ignore-daemonsets --delete-emptydir-data
```

드레인 대상 노드에는 **api 파드 2개 + web 1개 + Kafka 브로커 1개**가 올라가 있었다.
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

**노드 하나가 통째로 빠지는 동안 한 건도 깨지지 않았다.** Kafka 브로커까지 함께 축출됐는데도
API 응답에 영향이 없었다([[IMP-016]]에서 확인한 1대 장애 내성과 일관된다).

드레인 후 파드는 남은 2개 노드로 재배치됐고, `uncordon`으로 노드를 되돌렸다.

## 4. 무엇이 이것을 지켰나

| 설정 | 역할 |
|---|---|
| `PodDisruptionBudget minAvailable: 2` | 5개 중 **3개까지만** 동시 축출 허용 — 축출 요청을 API 서버가 거절/대기시킨다 |
| `maxUnavailable: 0` | 대체 파드가 Ready된 뒤에만 옛 파드 제거 |
| `topologySpreadConstraints` | 파드가 AZ에 흩어져 있어 **노드 하나가 전부를 갖고 있지 않았다** |
| `preStop 5s` + `deregistration_delay 10s` | ALB가 타깃을 빼는 동안 진행 중 요청 드레이닝 |

**마지막 두 줄이 없으면 PDB만으로는 부족하다.** PDB는 "몇 개까지 뺄 수 있나"를 통제할 뿐,
빠지는 그 파드로 가던 요청은 여전히 끊긴다.

## 5. ⚠️ 이 결과의 조건

- **파드가 5개였다.** `minAvailable: 2`라 여유가 컸다(3개까지 축출 가능).
  **최소치(3개)에서 같은 실험을 하면 결과가 다를 수 있다** — 축출이 대기하면서 드레인이 느려진다.
- ~~**PDB가 실제로 축출을 "막는" 장면은 보지 못했다.**~~ → **§7에서 봤다**(2026-08-26).
- ~~**Cluster Autoscaler가 없다.**~~ → **§7에서 CA 조건으로 재측정했다**(2026-08-26).
- **부하가 낮다**(4워커). S10 부하테스트에서 다시 봐야 한다.
- **1회 측정**이다.

## 6. 남은 것

- **드레인 중 다운타임의 정확한 측정**을 하지 않았다. "5xx가 없었다"까지이고,
  응답 지연이 어떻게 변했는지는 보지 않았다.
- ~~**Cluster Autoscaler 미설치**~~ → 2026-08-26에 도입하고 이 조건으로 재측정했다(§7, [[IMP-021]]).

---

## 7. 재측정 (2026-08-26) — Cluster Autoscaler 조건

§5가 남긴 두 한계가 여기서 해소됐다. 절차는 `scripts/resilience-test.sh --scenario drain`으로
고정했다 — 원래는 `kubectl drain`을 손으로 돌렸고, [[IMP-015]]가 같은 이유로 재현 불가였다([[TS-035]]).

```
클러스터  EKS 1.35 · m6i.large × 3 · Cluster Autoscaler 있음
부하      클러스터 안 k6, 25 rps × 4분 (open model)
행위      파드가 가장 많은 노드를 drain
```

⚠️ 생성기를 **드레인 대상 노드에서 nodeAffinity로 제외**했다. 그러지 않으면
*"장애로 요청이 끊겼다"*가 아니라 **"측정기가 함께 축출됐다"**를 보게 된다.

### 결과

```
총 요청   6,000
non-2xx        0
p95/p99   24ms / 40ms
장애 전 기준선 0
```

### ① PDB가 실제로 축출을 막았다

§5가 *"막는 장면은 보지 못했다"*고 남긴 것이 이번에 걸렸다.

```
error when evicting pods/"flowticket-api-85ccf58f5-l44js" -n "flowticket"
  (will retry after 5s): Cannot evict pod as it would violate the pod's disruption budget.
evicting pod flowticket/flowticket-api-85ccf58f5-l44js   ← 재시도
pod/flowticket-api-85ccf58f5-l44js evicted
node/ip-10-0-102-100... drained
```

`flowticket-api minAvailable=2` 상태에서 드레인이 **한 번 거부되고 재시도로 통과**했다.
PDB가 형식만 있는 것이 아니라 실제로 동작한다는 증거다.

### ② CA가 빠진 용량을 보충했다

§5가 *"노드가 추가되지 않으므로 남은 2개 노드에 다 들어갔다"*고 적은 조건이 바뀌었다.

```
drain T0  10:31:47Z
+30s      노드 3 → 4
10:32:52Z, 10:33:39Z   노드 2대 추가 → 최종 5대
```

⚠️ 추가된 2대가 **모두 AZ-c 그룹**에 붙었다. `balance-similar-node-groups`를 켜 두었는데도
한쪽에 몰렸다 — **원인은 확인하지 않았다**([[IMP-021]] §6).

### 이 재측정의 한계

- **1회 측정**이다.
- **부하 25 rps**로 여전히 낮다. 선착순 피크와는 거리가 멀다.
- **드레인 중 지연 변화**는 §6이 남긴 과제 그대로다 — p95/p99를 전체 구간으로만 냈고,
  드레인 구간만 떼어 보지 않았다.
- 파드 수 조건이 §5(5개)와 다르다. 이번에는 api 3파드 상태였고, 그래서 오히려
  `minAvailable=2`에 붙어 **PDB 차단이 관측됐다.**
