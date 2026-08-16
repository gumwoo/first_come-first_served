# 조회 캐시 실험 계획 — 인과 분리 → 비용 비중 → 캐시 효과

- 상태: **계획(미측정)** — 스크립트·프로토콜만 준비됐다. 숫자는 클러스터에서만 나온다.
- 선행: [phase1-saturation](../../benchmarks/phase1-saturation/README.md) (2026-08-11, knee 600~650 rps)
- 관련: [loadtest-plan.md](loadtest-plan.md), [[TS-021]](Hikari 풀 축소 판단)

## 0. 이 실험이 답하려는 것

Phase 1이 **명시적으로 미확정으로 남긴 질문**이 출발점이다.

> ⚠️ 인과 방향은 확정하지 않았다. 노드 CPU가 90%라 스레드가 느려져 커넥션을 오래 잡은 것인지,
> 커넥션이 부족해 요청이 밀려 CPU를 쓴 것인지 이 자료로는 갈리지 않는다.

| # | 질문 | 없으면 생기는 문제 |
|---|---|---|
| ① | 650 붕괴의 원인이 **CPU인가 커넥션인가** | 캐시가 맞는 처방인지조차 모른다 |
| ② | 엔드포인트별 **비용 비중** | "X% 빨라졌다"의 상한을 설명 못 한다 |
| ③ | 캐시 전후 **knee 이동** | ①②가 없으면 왜 그만큼인지 못 말한다 |

**순서를 지켜야 한다.** ③만 하면 숫자는 나오지만 해석이 안 된다.

## 1. 재현해야 하는 조건 (Phase 1과 동일)

비교값이 되려면 **환경이 같아야 한다.** 다르면 캐시 효과인지 환경 차이인지 못 가른다.

```
api HPA 3~7 / web HPA 2~4, requests 각 300m
노드 t3.large × 3 (allocatable 합 5.79 core)
Hikari 풀 = 파드당 5 (TS-021 판단)
k6는 클러스터 **밖**에서 실행 (TS-021 §7 자원 경합 회피)
```

⚠️ **k6를 클러스터 밖에서 돌리는 것은 의도된 선택이지 한계가 아니다.** 다만 클라이언트 p95에는
RTT·TLS·ALB·Next proxy가 포함되므로, **판단은 서버 p95와 서버 지표로 한다.**
(Phase 1 근거: 600 구간에서 클라 159ms vs 서버 0.05s로 경로 오버헤드는 작고 일정했다.
650에서 서버 p95가 0.05s→5.58s로 뛴 것은 경로로 설명되지 않는다.)

## 2. 고정 절차

콜드 스타트가 결과를 오염시킨 전례가 있어 Phase 1에서 고정한 절차를 그대로 쓴다.

```
각 측정마다:
  1) 워밍업  read-load-rate.js  RATE=300  RUN_FOR=45s   ← HPA를 올려 시작 조건 통일
  2) 측정    (해당 스크립트)     RUN_FOR=90s
  3) 서버 지표는 측정 구간(90초)의 값으로 읽는다
```

워밍업을 **별도 실행**으로 두는 이유: 같은 실행에 넣으면 k6 요약(p95·실패율)에 워밍업이 섞인다.

## 3. 단계별 설계

### 0단계 — 엔드포인트별 비용 (② 답)

동일 도착률에서 하나씩 떼어 잰다. **포화 아래(400 rps)** 에서 재야 큐잉이 비용을 부풀리지 않는다.

```bash
k6 run -e K6_BASE_URL=<base> -e EP=seats  -e RATE=400 -e RUN_FOR=90s infra/k6/read-load-endpoint.js
k6 run -e K6_BASE_URL=<base> -e EP=list   -e RATE=400 -e RUN_FOR=90s infra/k6/read-load-endpoint.js
k6 run -e K6_BASE_URL=<base> -e EP=detail -e RATE=400 -e RUN_FOR=90s infra/k6/read-load-endpoint.js
```

읽을 값: **api CPU(core), Hikari active/pending, DB 쿼리량, 서버 p95.**
`요청당 CPU = api CPU ÷ 실제 rps` 로 정규화해야 엔드포인트끼리 비교된다.

### 1단계 — CPU vs 커넥션 인과 분리 (① 답)

650 구간에서 **한 번에 한 변수만** 바꿔 어느 쪽이 원인인지 가른다.

| 실험 | 바꾸는 것 | CPU가 원인이면 | 커넥션이 원인이면 |
|---|---|---|---|
| 1-A | Hikari 풀 5 → 10 (파드당) | pending 줄지만 **knee 그대로** (CPU가 여전히 90%) | knee 상승 |
| 1-B | 노드 3 → 4대 (CPU만 증설) | knee 상승 | pending 여전, knee 제자리 |

⚠️ 1-A는 **DB max_connections 확인이 선행**이다. `api 7파드 × 10 = 70`이 RDS 상한을 넘으면
그 자체가 새 병목이 된다. 확인 후 진행한다.

⚠️ 두 실험 모두 "둘 다 조금씩" 나올 수 있다. 그러면 **서로를 악화시키는 구조**라는 것이 답이며,
그 경우에도 어느 쪽이 지배적인지는 기울기로 비교한다.

### 2단계 — 캐시 AS-IS/TO-BE (③ 답)

0단계에서 비중이 큰 엔드포인트부터 캐시한다(무엇을 캐시할지는 **0단계 결과가 정한다**).

| 시나리오 | 스크립트 | 목적 |
|---|---|---|
| A. 기존 혼합 50:30:20 | `read-load-rate.js` | Phase 1과 직접 비교 가능한 유일한 값 |
| B. browsing-only | `read-load-endpoint.js EP=browsing` | 캐시가 가장 잘 듣는 상한 |
| C. seat-map-only | `read-load-endpoint.js EP=seats` | 실시간성이 요구되는 경로의 하한 |

각 시나리오에서 **도착률을 550 → 600 → 650 → 700**으로 올려 knee 이동을 본다(Phase 1과 동일 격자).

`EP=browsing`은 list:detail을 **60:40**으로 섞는다 — 원래 혼합의 30:20을 정규화한
값이라 A와 비교가 성립한다. 임의로 50:50을 쓰면 두 시나리오가 다른 워크로드가 된다.

## 4. 수집 지표 · 쿼리

클러스터가 떠 있는 시간이 곧 비용이므로 **쿼리를 미리 확정해 둔다.**

| 지표 | 출처 |
|---|---|
| 컨테이너 CPU(core) | `container_cpu_usage_seconds_total` — Phase 1과 같은 것을 써야 비교된다 |
| Hikari active/pending | Micrometer `hikaricp_connections_active` / `hikaricp_connections_pending` |
| 서버 p95 | 서버 측 HTTP 지표(Grafana 기존 패널) |
| 클라 rps·실패율·p95 | k6 요약 JSON |

⚠️ **`container_cpu_usage_seconds_total`(cgroup 합)과 `kubectl top nodes`(노드 전체)는 재는
대상이 다르다.** Phase 1에서 5.23 core vs 6.00 core로 갈렸고 차이 0.77이 시스템 몫이다.
**표에 섞어 적지 않는다.**

⚠️ node-exporter는 Phase 1 시점에 **3대 중 1대만 수집**되고 있었다. 노드 CPU를 교차 확인하려면
먼저 이것부터 확인한다.

## 5. 기록 템플릿

`benchmarks/cache-experiment/README.md`에 아래 형식으로 남긴다. **예측치는 표에 넣지 않는다.**

```
| 단계 | 목표 rps | 실제 | 실패율 | 클라 p95 | 서버 p95 | api CPU | Hikari active/pending |
```

- 실측만 표에 넣는다. 추정은 문장으로 쓰고 "추정"이라고 명시한다.
- 무효였던 측정도 **지운다** 대신 사유와 함께 남긴다(Phase 1 §무효 측정과 같은 방식).

## 6. 비용 관리

클러스터 가동 시간 = 비용이다.

```
1. (클러스터 없이) 스크립트·프로토콜·쿼리 확정   ← 이 문서가 그 산출물
2. terraform apply
3. 0단계 → 1단계 → 2단계 연속 실행
4. 결과 기록 후 즉시 destroy
```

**올라간 뒤에 무엇을 잴지 정하지 않는다.** 그 시간이 전부 비용이다.

## 7. 아직 정하지 않은 것

- **캐시 무효화 방식.** 좌석맵은 SSE로 실시간 반영되는 경로라 TTL만으로는 부족할 수 있다.
  기존 Redis Pub/Sub 팬아웃을 재사용하는 안이 있으나 **유실 시 낡은 재고가 남는다** —
  TTL 병행이나 직접 eviction이 필요하다. 0단계 결과로 좌석맵을 캐시할지부터 정한 뒤 설계한다.
- **캐시 대상 확정.** 0단계 전에 정하지 않는다.
