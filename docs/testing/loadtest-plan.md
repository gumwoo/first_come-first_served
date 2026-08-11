# 부하·정합성 실증 계획 (S10)

- 작성: 2026-08-11
- 대상: EKS 데모 클러스터(t3.large × 3)
- 임계 기준: [`docs/rules/performance-rules.md`](../rules/performance-rules.md)
- 실행 절차: [`infra/k6/README.md`](../../infra/k6/README.md)

## 이 문서가 답하려는 질문

> **어디까지 버티는지 알고 있고, 한계를 넘었을 때 어디서부터 무너지는지 설명할 수 있는가?**

"몇 TPS 나왔다"는 이 문서의 목표가 아니다. 최대 처리량이 900 rps여도 상관없다.
**포화점과 최초 병목 자원을 실측으로 특정하는 것**, 그리고 **부하가 커져도 정합성이 깨지지 않는 것**이
증명 대상이다.

---

## 0. 전제 — 이미 실측된 사실

계획의 근거. **실측과 추정을 섞지 않는다.**

| 항목 | 값 | 구분 |
|---|---|---|
| 노드 capacity | 2000m × 3 = 6.00 core | 실측 |
| 노드 **allocatable** | **1930m × 3 = 5.79 core** | 실측 |
| 현재 requests 합 | **2.73 core** (flowticket 1.10 / kube-system 1.06 / kafka 0.22 / monitoring 0.20 / argocd 0.15) | 실측 |
| 462 rps 시 CPU 사용 | api 3.08 + web 0.85 = 3.93 core | 실측 (단, 아래 ⚠️) |
| 서버 p95 / p99 | **27ms / 78ms**, 5xx **0** | 실측 |
| 클라이언트 p95 | **2,769ms** (같은 시각) | 실측 |
| ALB 오류 | 502 **2,996**, Target_5XX **0**, TargetConnectionError **2,391** | 실측 |
| api | requests 300m, HPA 3~9, 타깃 60% | 매니페스트 |
| web | requests 100m, 실측 430m(**4.3배**), **HPA 없음 / 2파드 고정** | 실측 |
| 대기열 | capacity 100 = **동시 ADMITTED 상한**(이탈·만료 시 슬롯 반환·재승격), 승격 주기 1.5초, admit-ttl 300초 | 코드 |
| 좌석 HOLD | `queueService.isAdmitted(queueToken, eventId)` 통과 필수 | 코드 |
| 대기 화면 | **SSE 우선**, 실패 시 **2초 폴백 폴링** | 코드 |
| Java | **17** (가상 스레드 불가) | build.gradle |
| 기존 동시성 테스트 | `newFixedThreadPool` + `CountDownLatch` 동시 출발 패턴 존재 | 코드 |

> ⚠️ **`3.93 core / 462 rps`로 전체 한계를 선형 추정하지 않는다.** 그 시점에 요청 2,996건이 ALB에서
> 502로 떨어지고 있었다. 즉 "462 rps를 온전히 처리하면서 쓴 CPU"가 아니며, 병목이 web이라 api는
> 자기 한계까지 가보지도 못했다. 한계값은 Phase 1에서 **측정으로** 구한다.

### 구조상 알아둘 것 — 제약이 아니라 설계 의도

```
10,000명 대기열 진입
        ↓
WAITING 9,900명            ← 여기까지는 부하가 온다
        ↓
동시 ADMITTED 최대 약 100   ← 슬롯 회전으로 계속 교체
        ↓
그 100명만 좌석 HOLD 로직 진입
```

**"10,000명이 동시에 HOLD까지 도달하지 않는다"는 대기열을 둔 목적 그 자체다.** 좌석 DB에 10,000명이
그대로 몰리지 않게 앞단에서 제한하는 것. 따라서 좌석 로직 자체의 동시성은 **대기열을 우회한 별도
통합테스트**로 검증한다(§3-4).

---

## Phase 0 — 준비

### 0-0. AS-IS web 병목 박제 ⚠️ **고치기 전에 먼저**

web을 고치고 나면 "왜 고쳤는가"의 실측 근거가 사라진다. 이 저장소가 IMP에서 계속 해온 before/after
패턴이다.

```
현재 구성(web 2파드 고정) 그대로 arrival-rate 램프
→ "몇 rps부터 TargetConnectionError / 502가 증가하는가"를 박제
```

이미 가진 증거(502 2,996 / Target_5XX 0 / TargetConnectionError 2,391)에 **오류 시작 지점**을 더한다.

### 0-1. Scheduling budget

스케줄링은 **실사용량이 아니라 requests와 allocatable**로 결정된다.

```
Scheduling budget = allocatable 5.79 - requests 합 2.73 = 3.06 core
```

- api가 HPA 최대 9파드로 가면 `9 × 300m = 2.7 core` → 지금(3파드 0.9)보다 **+1.8 core** 필요
- 남는 여유 약 1.26 core 안에서 web HPA를 설계해야 한다
- 넘으면 파드가 `Pending`에 걸린다 — 시험이 아니라 스케줄링 사고

### 0-2. Runtime budget

Scheduling과 **별개로** 봐야 한다. 파드가 뜨는 것과 빠른 것은 다른 문제다.

- 실제 CPU usage (`container_cpu_usage_seconds_total`)
- CPU throttling
- **t3 CPU credit 잔량 / steal** ← 아직 부하 중에 확인한 적 없음

### 0-3. web 병목 개선

| 안 | 내용 | 판단 |
|---|---|---|
| A | requests를 실측(430m) 기준으로 현실화 + HPA | **추천.** `100m`은 실측의 1/4로 HPA 여부와 무관하게 틀린 값 |
| B | replicas 2 → 4 고정 | 단순. 부하에 반응하지 않음 |
| C | 노드 추가 | 여유 확보. 비용 발생 |

0-1 결과로 결정. **이걸 안 하면 무릎의 원인이 web에 가려 api의 진짜 한계를 못 본다.**

### 0-4. 관측 하네스

Prometheus remote write는 활성화됨(#211) → k6 지표가 서버 지표와 **같은 시간축**에 들어온다.

```
K6_PROMETHEUS_RW_SERVER_URL=http://<prometheus>/api/v1/write \
k6 run -o experimental-prometheus-rw ...
```

통합 대시보드 패널:

| 행 | 패널 |
|---|---|
| 1 | k6 RPS · p95/p99 · 실패율 · VU |
| 2 | api 파드 수(HPA 곡선) · HPA CPU% |
| 3 | **클라이언트 p95 vs 서버 p95 겹쳐서** — 두 선이 벌어지는 지점이 "앱은 멀쩡한데 경로가 막혔다" |
| 4 | Hikari active/pending · JVM 힙 · GC |
| 5 | web 파드 CPU/수 |
| 6 | Redis `queue:admitcount` |

### 0-5. 부하용 계정·데이터 준비

- DB 직접 시딩(동일 bcrypt 해시 재사용 — **로그인 CPU가 측정에 섞이지 않게**)
- **JWT 사전 발급 → 파일.** 측정 구간에 로그인 없음
- 식별 가능한 이메일(`loadtest+{n}@…`)로 사후 정리 가능하게
- 시드 계정·데이터는 실사용이 아님을 결과 문서에 명시

---

## Phase 1 — 포화점 탐색 (Load)

### 1-1. VU 램프 (closed model)

```
VU: 100 → 200 → 300 → 500 → 750 → 1000, 각 3분 (워밍업 런은 버림)
1000에서도 처리량이 계속 늘면 1500/2000으로 연장
```

### 1-2. arrival-rate 램프 (open model) ⚠️ **필수**

VU 기반은 **서버가 느려지면 부하가 스스로 줄어든다.**

```
서버 느려짐 → VU 한 사이클 길어짐 → 실제 RPS 감소 → 시스템이 덜 맞는다
```

포화점을 찾으려면 도착률을 강제로 유지하는 open model이 함께 있어야 한다
(k6 `constant-arrival-rate` / `ramping-arrival-rate`).

```
100 → 200 → 300 → 400 → 500 rps ... 도착률 고정
```

**"VU 램프에서 무릎을 찾았고, arrival-rate 모델로 같은 구간을 재확인했다"**가 목표 서술이다.

### 1-3. 기록 표 — 클라이언트와 서버를 반드시 분리

```
VU/rate | RPS | 클라p95 | 서버p95 | 서버p99 | 실패율 | api파드 | api CPU | web CPU | Hikari act/pend
```

```
client p95 = DNS + TCP + TLS + ALB + web + proxy + API + network
server p95 = API 애플리케이션 처리시간
```

**둘이 2.7초 vs 27ms면 완전히 다른 문제를 보고 있는 것이다.** 서버 p95가 진짜 무릎이고,
클라이언트 p95는 종단 경험이다. 둘 다 사실이지만 다른 것을 말한다.

### 1-4. 산출 문장 형태

> "약 N rps까지 서버 p95 300ms 이하로 선형 증가했고 그 이후 평탄화됐다. Hikari pending은 0이었고
> __ CPU가 먼저 포화되어 현재 구성의 첫 병목은 __로 판단한다."

---

## Phase 2 — HPA A/B (HPA가 값을 했는가)

"3→9로 늘었다"는 사실이지 효과가 아니다. **늘어나서 사용자 요청이 좋아졌는가**를 재야 한다.

```
① 3파드 고정   + 동일 arrival rate  → RPS, 서버 p95
② HPA 3~9 허용 + 동일 arrival rate  → RPS, 서버 p95
```

⚠️ **반드시 동일 arrival rate로 비교한다.** VU 고정으로 비교하면 느린 쪽이 알아서 부하를 덜 넣어
A/B가 아니라 서로 다른 두 실험이 된다.

⚠️ **ArgoCD `selfHeal: true`라 HPA를 손으로 고정하면 약 3분 안에 되돌아온다.** Git 커밋으로 바꾸거나
시험 동안 selfHeal을 일시 해제해야 한다([[TS-022]]).

HPA가 개선을 못 만들어도 가치가 있다 — "파드를 늘려도 나아지지 않았고, RDS/Redis/Kafka 중 무엇이
공유 병목인지 추적했다"가 되기 때문이다.

---

## Phase 3 — 정합성 (성능 시험이 아니다) · **최우선 가치**

빠른데 틀린 시스템은 쓸 수 없다. 초과판매는 지연이 아니라 **사고**다.

### 3-1. 대기열 진입 burst

```
10,000명이 거의 동시에 POST /events/{id}/queue/token
검증: Redis ZSET 삽입, 토큰 유일성(1인 1토큰), over-admit 0(admitcount ≤ 100),
      진입 rank 순서 == 승격 순서
```

### 3-2. 대기 중 SSE 연결

대기 화면은 SSE 우선이다. **CPU보다 FD·연결 한계가 먼저 터질 수 있다.**

```
관측: 동시 연결 수 / Pod별 SSE 연결 분포 / Pod 메모리 / Pod CPU
      open file descriptors / 연결 성공률 / 유지율 / 재연결률
      Redis pub/sub 팬아웃 지연
```

이 프로젝트에 특징적인 항목이다 — SSE 팬아웃을 Redis pub/sub으로 짰으므로([[TS-023]] §3)
연결이 많아질 때 그것이 버티는지가 진짜 질문이다.

### 3-3. 폴백 폴링

```
2초 주기 폴링(코드 실측값)
→ 9,900명이면 약 4,950 rps ← 클러스터 처리량을 훨씬 초과
```

이것을 §3-1과 섞으면 **"대기열이 10,000명을 수용하는가"가 아니라 "status 폴링을 버티는가"** 시험이
된다. 반드시 분리해서 status 엔드포인트의 처리량·지연·실패율만 본다.

### 3-4. 좌석 경합 — 두 층

> ✅ **API 층 실측 완료(2026-08-11)** — 결과: [`benchmarks/hold-contention/`](../../benchmarks/hold-contention/README.md)
> 1석 vs 100명 동시, 20회 전승으로 초과판매 0. 서비스 층(대기열 우회)은 미실시.

**(a) 실사용 API 경로**

```
동시 ADMITTED 100 + 슬롯 회전, 10석에 경합
검증: 성공 == 10, 실패는 정상 에러코드(500 아님)
      DB 사후: 한 좌석에 HELD 2건 없음
반복: 20회  ← 1/20로 깨지는 것이 가장 위험하다
```

**(b) 좌석 동시성 단위 (대기열 우회)**

```
기존 SeatInventoryIntegrationTest 확장 (뼈대 이미 있음)
newFixedThreadPool + CountDownLatch 동시 출발
100 → 300 → 500 → 1000
```

⚠️ **10,000 플랫폼 스레드를 만들지 않는다.** Java 17이라 가상 스레드가 없고, OS 스레드 10,000개는
테스트 머신을 먼저 망가뜨린다. 핵심은 스레드 개수가 아니라 **latch 동시 출발**이다.

### 3-5. 나머지 정합성 규칙

기존 테스트(`SeatQuotaIntegrationTest`, `PaymentIdempotencyIntegrationTest`,
`RefundIdempotencyIntegrationTest`)가 **부하 상태에서도 유지되는지** 확인한다.

```
quota 초과 = 0 / 결제 멱등 / 동일 hold 중복 결제 = 0 / 콜백 중복 = 0 / 아웃박스 유실 = 0
```

---

## Phase 4 — Spike (선착순의 정체성)

> ⏳ **1차 실측 완료, 미완료(2026-08-12)** — 결과: [`benchmarks/spike-queue/`](../../benchmarks/spike-queue/README.md)
> 3,000명(정원의 30배) 동시 진입 3회. 관측된 모든 샘플에서 `admitcount` ≤ 100,
> 종료 스냅샷에서 추월 흔적 0, 5xx 0.
> **완료 처리하지 않는 이유**: 명제 ③("나머지는 에러가 아니라 대기")이 run-2에서 10건 깨졌고,
> 원인이 앱의 실제 결함으로 확정됐다 → [TS-024](../troubleshooting/TS-024-queue-admit-visibility-gap.md).
> 수정 후 재측정으로 닫는다.

티켓 오픈 순간은 **정의상 스파이크**다. 평상시 0에서 순간 폭주로 튀는 것이 이 시스템이 존재하는 이유다.

```
검증: over-admit 0, 순서 보존, 진입한 사용자의 조회는 여전히 빠름
      나머지는 에러가 아니라 대기 상태
```

**규모에 대한 입장:**

> Lua 원자성 때문에 동시 요청량 증가가 over-admit **정합성**을 깨뜨릴 가능성은 낮지만,
> **처리량·지연·연결 자원은 별도 한계**를 갖는다.

정합성이 유지될 이유와 성능이 유지될 이유는 다르다. 규모를 키운 실험은 후자를 재는 것이다.

---

## Phase 5 — Stress + 장애 주입

**Stress**: Phase 1 램프의 연장. 별도 스크립트 없음. 목적은 숫자가 아니라 **실패 모양**이다.

```
한계 초과 시 깨끗한 거절(429/대기)인가, 502·타임아웃으로 새는가
부하 제거 후 복구 시간
```

**장애 주입** ([[TS-023]] §4 확장 — 이번엔 **지속 부하 중에**):

```
Kafka 브로커 1대 SIGKILL
기록: 장애 전 p95 / 장애 순간 p95 / 회복 시간
      아웃박스 PENDING 최대, Consumer Lag 최대
      유실 건수, 중복 건수, 최종 수렴 여부
```

TS-023에서 이미 **"타임아웃 건수 == Kafka 중복 건수"**를 관측했다(15↔+15, 3↔+3).
at-least-once가 문서가 아니라 관측으로 확인된 자료다.

---

## Phase 6 — Soak 60분 (선택)

```
무릎의 60% 수준으로 60분 지속
검증(유계성): 홀드·주문 행 수, Redis 대기열 키, 아웃박스 PENDING,
              Consumer Lag, JVM 힙 — 우상향하지 않을 것
```

정석은 4~24시간이나 클러스터 시간이 비용이라 축소한다.
**결과에 "60분 기준, 그 이상 미검증"을 반드시 명시한다** — 60분으로는 느린 누수를 잡지 못한다.

---

## 산출물

| 종류 | 내용 |
|---|---|
| 코드 | `infra/k6/{queue-burst,queue-sse,queue-poll,hold-contention,spike}.js`, arrival-rate 변형, 시딩·관측 스크립트 |
| 테스트 | `SeatInventoryIntegrationTest` 규모 확장 |
| 매니페스트 | web requests/HPA, 통합 대시보드 ConfigMap |
| 문서 | 결과 기록, IMP(개선 시), TS(사고 시) |

## 정직성 원칙 — 결과 문서에 그대로 명시한다

- **측정 지점을 항상 밝힌다** — 클라이언트 p95와 서버 p95는 다른 것을 잰다
- **k6는 노트북에서 돈다** — 인터넷·TLS·로컬 CPU 포함. 별도 dev 서버는 비용상 만들지 않는다
- **부하는 운영 클러스터 자체에 건다** — 실사용자가 없어 가능한 선택
- **VU → 실사용자 환산은 가정이다** — think time을 명시하고 실측과 분리해 표기
- **반복 횟수를 적는다** — 정합성 20회, 성능 최소 3회
- **개선 전후는 동일 조건에서만 비교한다** — 같은 arrival rate·요청 구성·데이터·지속시간
- **시드 계정·데이터는 실사용이 아니다**
- **최대 처리량 경쟁을 하지 않는다** — 포화점과 최초 병목을 특정하는 것이 목표다
