# FlowTicket

선착순 공연 예매 시스템입니다. 대기열·좌석 선점·주문·결제·환불까지 예매 흐름 전체를 다루고, AWS EKS에 올려 부하·장애를 걸어 실제로 잰 결과를 남깁니다.

## 한눈에 보기

| 주장 | 증거 | 근거 |
|---|---|---|
| 동시성 정합성 | 입장자 100명이 동시에 좌석 선점·주문·결제(경합·더블클릭·결제 실패 후 재시도·1인 한도 포함) — 판정 3회 정합성 위반 0 | [IMP-030](docs/improvements/IMP-030-booking-e2e-correctness.md) |
| 10만 명 / 10초 진입 | 서버 도착(ALB access log) 기준 진입 100,000건 정상 처리, 정원 초과·정합성 위반 0 — 통합 구조 3회, 운영 기본값(대기열 분리 구조) 1회 | [IMP-031](docs/improvements/IMP-031-integrated-platform-100k.md) · [IMP-035](docs/improvements/IMP-035-b-config-100k-entry.md) |
| 자동 확장 | 같은 실행에서 폴링이 몰리자 Pod 3→9·Node 6→9로 늘었다가 다시 줄어듦([아래 그림](#10만-명--10초--보낸-숫자가-아니라-받은-숫자로)). 통합 구조 판정 run에서 노드 9 고정 대비 노드-시간 11~13% 절감(계산) | [IMP-031](docs/improvements/IMP-031-integrated-platform-100k.md) · [IMP-032](docs/improvements/IMP-032-autoscaling-node-hours.md) |
| 무중단 배포·장애 | 롤링 배포 중 5xx 0(web 경유 경로), RDS·Redis Multi-AZ 페일오버에서 파드 재시작 없이 복구 | [TS-035](docs/troubleshooting/TS-035-rolling-deregistration-race.md) · [TS-037](docs/troubleshooting/TS-037-rds-redis-failover-app-behavior.md) |
| 비용 판단 | 측정 세션 시간당 비용(약 $2.06, 계산)을 항목별로 나눠, 노드 줄이기보다 부하 발생기 켜 두는 시간·세션 길이를 먼저 줄일 곳으로 정함 | [IMP-040](docs/improvements/IMP-040-session-cost-breakdown.md) |

### 이 README가 주장하지 않는 것

- **운영 트래픽이 아닙니다.** 모든 부하는 k6로 만든 합성 트래픽이고, 클러스터는 측정할 때만 올렸다가 철거합니다.
- **반복 횟수가 적습니다.** 통합 구조의 10만 진입은 3회, 운영 기본값(대기열 분리 구조)은 1회 판정입니다. 분리 구조에서 폴링 폭증·예매를 10만 진입과 함께 건 측정은 없습니다.
- **재지 않은 경로가 있습니다.** ALB가 바로 보내는 대기열 경로의 롤링 무중단은 재지 않았습니다.
- **설정값 재검토는 클라우드 검증 전입니다.** 요청량·스레드·노드 수 재검토([IMP-036](docs/improvements/IMP-036-local-threads-and-booking-burst.md)~[IMP-039](docs/improvements/IMP-039-peace-consolidate-open-separate.md), [IMP-041](docs/improvements/IMP-041-peace-api-two.md))는 로컬 상대 비교와 스케줄링 시뮬레이션입니다.
- **비용은 계산값입니다.** 공개 가격표 정가(온디맨드) 기준이고 사용량 과금(데이터 처리·전송)은 넣지 않았습니다.

## 왜 만들었나

SI 프로젝트에서 요구사항대로 기능을 만드는 경험은 쌓았지만, 그 기능이 트래픽이 몰려도 제대로 동작하는지 직접 확인할 기회는 드물었습니다. 선착순 예매는 정원보다 많은 사람이 입장하거나 같은 좌석이 두 번 팔리는 문제가 화면만 봐서는 드러나지 않습니다. 그래서 동시성 문제를 막는 구현부터 시작했고, 테스트 통과에서 멈추지 않고 AWS에 직접 올려 부하·배포·확장·장애 상황에서도 정상인지 쟀습니다. 잴 때는 부하 도구가 보냈다고 기록한 숫자가 아니라 서버가 실제로 받은 요청을 기준으로 봤고, 기준에 못 미친 실행도 그대로 남겼습니다.

## 서비스 흐름

```text
공연 탐색 → 대기열 진입 → 입장 허용 → 좌석 선점 → 주문 생성 → 결제 → 예매 내역 / 환불
```

운영자는 공연·좌석을 관리하고, 주문·아웃박스·알림 상태를 확인합니다. 공연 기본 정보는 KOPIS OpenAPI를 백엔드 배치가 동기화하며, 거래에 필요한 좌석·가격·재고는 FlowTicket이 직접 관리합니다.

## 실증 — 눌러 보고 잽니다

선착순 예매는 **틀려도 조용히 틀립니다.** 좌석이 두 번 팔리거나 대기열 순번이 뒤집혀도 화면은 멀쩡해 보입니다. 그래서 막는 층을 나누고, 마지막 층은 **실제로 눌러 봅니다.**

### 10만 명 / 10초 — 보낸 숫자가 아니라 받은 숫자로

부하 도구가 "보냈다"고 기록한 요청 수는 서버 도착의 증거가 아닙니다. 그래서 ALB access log에 찍힌 **서버 도착 시각**으로 10초 창 안의 진입을 셌습니다.

같은 실행 안에서 공연 오픈 전 Git PR로 Pod를 미리 늘리고, 오픈 직후 HPA·Cluster Autoscaler에 넘긴 뒤, 대기자 폴링이 몰리자 다시 Pod와 Node가 늘었다가 줄어드는 흐름까지 이어서 확인했습니다. 세 번 반복해 세 번 모두 진입 100,000건 정상 처리, 정원 초과·정합성 위반 0이었습니다([IMP-031](docs/improvements/IMP-031-integrated-platform-100k.md)).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/scaling-pf-r2-dark.png">
  <img alt="통합 구조 10만 명 판정 run pf-r2의 api Pod 수와 Ready 노드 수. 오픈 약 15분 전 사전 확장 PR 머지로 둘 다 9가 되고, 오픈 뒤 사전 확장을 끄자 api Pod가 3까지, 노드가 6까지 줄어든다. 오픈 15~23분의 폴링 폭증(7,000/s) 동안 api Pod와 노드가 다시 9까지 늘었고, 폭증 뒤 api Pod 3·노드 4로 줄었다." src="docs/assets/scaling-pf-r2-light.png">
</picture>

<sub>통합 구조 판정 run pf-r2의 실측값. 사전 확장 구간은 15초 해상도·api 가용 수, 오픈 약 3분 전부터는 20초 해상도·HPA 현재 replicas(Running 수와 다를 수 있음)이고, 그 사이(약 −13~−3분)는 표본이 없어 마지막 값을 이어 그렸습니다. 데이터 [`imp031-pf-r2-scaling.csv`](docs/assets/imp031-pf-r2-scaling.csv) · 그리는 스크립트 [`scaling-chart.py`](docs/assets/scaling-chart.py)</sub>

운영 기본값을 대기열 분리 구조로 바꾼 뒤에는 진입을 받는 Pod가 달라졌으므로(api 9 → 대기열 6) 이전 수치를 그대로 붙이지 않았습니다. 같은 판정 기준을 먼저 적어 두고 1회 다시 쟀습니다(진입만 — 폴링 폭증·예매는 함께 돌리지 않음). 진입 100,000건 전부 정상 처리, 정원 초과·정합성 위반 0이었습니다([IMP-035](docs/improvements/IMP-035-b-config-100k-entry.md)).

### 대기열 분리 — 결과가 갈렸을 때 결론보다 측정 절차를 먼저 고쳤다

폴링이 몰리면 대기열과 예매가 같은 Pod에서 함께 늘어나 같은 스레드를 나눠 씁니다. 코드를 서비스로 쪼개기 전에, 같은 이미지로 대기열 전용 Deployment만 두고(overlay `components:` 한 줄 — 대기열 Service의 대상을 바꾼다) 같은 Pod 예산 9에서 통합 구조와 비교했습니다.

첫 측정에서는 같은 통합 구조인데 결과가 정반대로 갈렸습니다. 원시 지표로 나눠 보니 아키텍처가 아니라 **측정 절차**의 차이였습니다 — 한 run은 예매 경로가 처음 실행되는 순간에(JIT·커넥션 대기) 기준 구간이 걸렸고, 다른 run은 폭증 시작이 늦어져 예매 시점이 어긋났습니다. 판정 지표(폭증 중 ÷ 폭증 전 예매 p95)와 2배 기준은 그대로 두고, 절차(시작 Pod 워밍업, 폭증 대비 예매 시점, 각 구조의 안정 상태에서 시작)를 고쳐 **다시 사전 등록한 뒤** 구조별 2회씩 재측정했습니다 — 2회 반복에 맞춰 가장 보수적인 쌍으로 비교했습니다.
폭증 중 새로 뜬 Pod는 일부러 데우지 않았습니다(통합 구조의 실제 동작이라 남김). 이 선택은 분리 쪽에 유리하게 기운다는 점을 사전 등록에 밝혀 두었습니다.

| 지표 (구조별 2회) | 통합 | 대기열 전용 Deployment |
|---|---|---|
| 폭증 중 예매 p95 (선점·주문·결제) | 1.6~2.6초 | 184~272ms |
| 폭증 중 대기열 상태 조회 p95 | 13~18ms | 259~523ms |
| 노드 비용(계산, run 25분) | $0.32 | $0.37~0.41 |

통합 구조에서는 폭증으로 새로 생성된 Pod가 예매 요청의 약 61%를 받았고, 그 Pod들의 서버 처리 시간이 기존 Pod보다 길었습니다(실측 — 세션 중 Prometheus 조회, 원시 응답은 미보존). 분리 구조는 예매 Pod 수가 고정이라 이 경로가 없습니다.
대신 폭증 중 대기열 Pod가 HPA 상한(6)에 닿은 채 상태 조회가 느려졌습니다 — 상한이 원인인지는 아직 검증하지 않았습니다. 노드는 한가할 때도 1~2대 더 들었습니다(대기열 최소 Pod 2개 몫).
그래서 결론은 "분리 쪽 개선(운영 관점, 각 2회)"으로 한정했습니다([IMP-033](docs/improvements/IMP-033-queue-split-ab.md)). 대기열 쪽 자원을 줄여 비용을 낮추려던 다음 실험(B′)은 노드-시간 목표에 닿지 못했고([IMP-034](docs/improvements/IMP-034-bprime-queue-resources-g1.md)), **그 비용을 감수하고 분리 구조를 운영 기본값으로 정했습니다**([ADR-027](docs/decisions/ADR-027-queue-dedicated-deployment.md)) — 예매를 받는 Pod가 대기열 폭증에 끌려가지 않는 쪽을 택한 것입니다.

### 롤링 배포 중 502 — 흔한 처방이 원인이 아니었다

통상 `deregistration_delay`를 늘리지만 **지표는 그 값과 무관함을 보여줬습니다.** 실패 지연이 정확히 `10,000ms`에 몰렸고 `Target_5XX = 0` — 애플리케이션은 5xx를 낸 적이 없었습니다. 문제 구간은 타깃이 `deregistering`이 되기 **전**, 파드는 이미 응답을 멈췄는데 ALB는 아직 정상 타깃으로 아는 구간이었습니다.

늘려야 할 것은 드레이닝이 아니라 **컨테이너가 살아 있는 시간**(`preStop`)이었습니다.

| 조건 | 요청 | 5xx |
|---|---|---|
| `preStop` 5s | 6,001 | 실패 |
| `preStop` 25s | 6,001 | **0** |

*다른 클러스터 조건에서는 `preStop 5s`에서도 재현되지 않아, 25초를 환경 불변의 최소값으로 보지는 않습니다. ([TS-035](docs/troubleshooting/TS-035-rolling-deregistration-race.md) · [IMP-015](docs/improvements/IMP-015-rolling-zero-downtime.md))*

### 세 층으로 막습니다

| 층 | 무엇을 막나 | 어떻게 |
|---|---|---|
| **코드** | 동시성 정합성 | Redis Lua로 대기열 승격을 원자화, 좌석·주문은 조건부 `UPDATE` + 영향 행 수 검증, 이벤트는 Transactional Outbox |
| **CI** | 구조 드리프트 | 계약(enum·API·이벤트·계층)을 파일로 두고 정적 검사. **일부러 만든 위반 fixture 75개로 "규칙이 실제로 잡는지"를 메타테스트가 판정**합니다 — 규칙을 믿지 않고 규칙을 시험합니다 |
| **실측** | 운영 중 장애·부하 | 클러스터를 띄우고 장애를 **주입**하고 부하를 겁니다. 롤링 배포·노드 오토스케일([IMP-021](docs/improvements/IMP-021-cluster-autoscaler-node-scaling.md))·RDS/Redis 페일오버([TS-037](docs/troubleshooting/TS-037-rds-redis-failover-app-behavior.md))·10만 명 / 10초 진입([IMP-031](docs/improvements/IMP-031-integrated-platform-100k.md)·[IMP-035](docs/improvements/IMP-035-b-config-100k-entry.md)) |

**좋아진 수치만 싣지 않습니다.** RDS 페일오버에서 찾은 결함(30초를 다 기다린 뒤 500)을 고쳐 같은 장애를 다시 쟀습니다. 결과는 이렇습니다.

```
실패 요청의 대기 시간 총합   4,514초 → 1,341초   (−70%)
실패 건수                      181 → 400건       (+121%)
```

**개선이 아니라 트레이드오프였습니다.** 그렇게 기록했고, 한 번 잰 값으로는 채택하지 않았습니다([IMP-022](docs/improvements/IMP-022-rds-connection-timeout.md)). 측정 도구가 조용히 틀려 결론이 뒤집힐 뻔한 일도 있었고, 그것도 남겼습니다([TS-036](docs/troubleshooting/TS-036-measurement-tooling-false-failures.md)).

## 검증 결과 전체

| 검증 | 결과(실측, 계산은 표기) | 근거 |
|---|---|---|
| 대기열 진입 10만 명 / 10초 + 자동 확장 | 판정 3회 모두 진입 100,000건 정상 처리, 정원 초과·정합성 위반 0. 같은 실행에서 폴링이 몰리자 Pod 3→9·Node 6→9로 확장했다가 다시 축소 | [IMP-031](docs/improvements/IMP-031-integrated-platform-100k.md) |
| 대기열 분리 구조(운영 기본값)의 10만 명 / 10초 | 오픈 전 Git PR로 대기열 Pod 2→6, 진입 100,000건 정상 처리(진입 p95 46ms·대기 상태 조회 p95 8ms, 서버 처리 시간), 정원 초과·정합성 위반 0. 오픈 뒤 HPA에 넘겨 대기열 6→2·Node 9→7 축소까지 기록. 판정 1회, 폴링 폭증·예매는 함께 돌리지 않음. 판정 도구가 구간 뒤 조회 오류로 '판정 불가'를 냈고, 원인을 확인한 뒤 충족으로 해석(IMP-035 §5) | [IMP-035](docs/improvements/IMP-035-b-config-100k-entry.md) |
| 자동 확장 비용 효과 (계산) | 통합 구조 판정 run(IMP-031)의 실측 노드 곡선을 시간으로 적분해 노드 9 고정 대비 노드-시간 11~13% 절감. 분리 구조 10만 진입 run(IMP-035)의 노드-시간(9 고정 대비 절감)은 계산하지 않음. 남은 초과의 절반 이상은 폭증 뒤 CA 축소 대기(약 11분) | [IMP-032](docs/improvements/IMP-032-autoscaling-node-hours.md) |
| 대기열 분리 비교 (같은 Pod 예산 9) | 폴링 폭증(7,000/s) 중 예매 p95: 통합 1.6~2.6초 vs 분리 184~272ms(각 2회, 오류·정합성 위반 0). 사전 등록 판정 "분리 쪽 개선"(운영 관점 — 분리만의 효과는 떼어 내지 않음). 대가로 상태 조회 p95 13~18 → 259~523ms, 노드 비용 +15~28%(계산) | [IMP-033](docs/improvements/IMP-033-queue-split-ab.md) · [ADR-027](docs/decisions/ADR-027-queue-dedicated-deployment.md) |
| 입장자 동시 예매 | 입장자 100명이 동시에 좌석 선점·주문·결제(경합·더블클릭·결제 실패 후 재시도·1인 한도 포함), 판정 3회 정합성 위반 0 | [IMP-030](docs/improvements/IMP-030-booking-e2e-correctness.md) |
| 무중단 배포 | 롤링 배포 중 5xx → 0(web `preStop` 25s, web 경유 경로). ALB가 바로 보내는 대기열 경로는 미측정 | [TS-035](docs/troubleshooting/TS-035-rolling-deregistration-race.md) · [IMP-015](docs/improvements/IMP-015-rolling-zero-downtime.md) |
| 장애 주입 | RDS·Redis Multi-AZ 페일오버에서 파드 재시작 없이 자동 복구, 노드 오토스케일로 Pending 파드 수용 | [TS-037](docs/troubleshooting/TS-037-rds-redis-failover-app-behavior.md) · [IMP-021](docs/improvements/IMP-021-cluster-autoscaler-node-scaling.md) |
| 대기열 토큰 정합성 보강 | 토큰 소유권(남의 입장 토큰 차단), 재발급·이탈 경합(1인 1토큰), 대기 토큰 수명(폴링 연장·만료 토큰 승격 차단)을 통합 테스트로 검증 | [TS-043](docs/troubleshooting/TS-043-queue-token-ownership.md) · [TS-044](docs/troubleshooting/TS-044-queue-takeover-leave-race.md) · [TS-045](docs/troubleshooting/TS-045-queue-token-lifecycle.md) |
| 설정값 근거 재검토 (로컬·시뮬레이션) | 요청량·스레드·DB 풀·노드 수 근거를 원자료 재분석·로컬 실험·kwok 시뮬레이션으로 재확인. Tomcat 스레드 400은 개선 없음 → 200 유지. 평시 노드 5 → 4대 후보(예매 api 300m)는 폴링 3,000/s와 예매 묶음이 겹친 조건에서 api·대기열 동거 시 폴링 p99 10ms대 → 0.6~1.3초로 기각, 평시 합치기·평시 api 2개는 보류 (로컬 상대 비교·조건당 1~2회, 노드 수는 시뮬레이션) | [IMP-036](docs/improvements/IMP-036-local-threads-and-booking-burst.md)~[IMP-039](docs/improvements/IMP-039-peace-consolidate-open-separate.md) · [IMP-041](docs/improvements/IMP-041-peace-api-two.md) |
| 측정 세션 비용 분해 (계산) | 평시 노드 5대·부하 발생기 3대 기준 세션 시간당 약 $2.06 — 노드 29%·발생기 28%·Redis 19%·NAT 9%. 평시 노드 1대는 약 6% | [IMP-040](docs/improvements/IMP-040-session-cost-breakdown.md) |

### 측정 환경

같은 "10만 명" 측정이라도 세션마다 구성이 다릅니다. 수치를 비교할 때는 이 표를 함께 봅니다.

| 측정 | 날짜 | 노드 | Redis | RDS | Kafka | 부하 발생기 | api 이미지 | 판정 |
|---|---|---|---|---|---|---|---|---|
| [IMP-030](docs/improvements/IMP-030-booking-e2e-correctness.md) 입장자 동시 예매 | 2026-10-07 | m6i.large(측정 시 3대) | cache.t4g.micro × 2 | db.t4g.micro Multi-AZ | 있음 | k6, EC2 c6i.xlarge × 1 | `d14e36b`(매니페스트상 `874a980`과 같은 내용) | 3회 |
| [IMP-031](docs/improvements/IMP-031-integrated-platform-100k.md) 통합 10만 + 자동 확장 | 2026-10-08 | m6i.large(AZ당 최대 3, 합 9) | cache.t4g.micro × 2 | db.t4g.micro Multi-AZ | 있음 | k6, EC2 c6i.xlarge × 3 | `419c96b` | 3회 |
| [IMP-033](docs/improvements/IMP-033-queue-split-ab.md) 대기열 분리 비교 | 2026-10-14 | m6i.large(AZ당 최대 3) | cache.m7g.large × 2 | db.t4g.micro Multi-AZ(철거 로그) | 있음 | k6, EC2 c6i.xlarge × 3(철거 로그) | `2f1f7ff` | 구조별 2회 |
| [IMP-035](docs/improvements/IMP-035-b-config-100k-entry.md) 분리 구조 10만 | 2026-10-17 | m6i.large(AZ당 최대 3, 합 9) | cache.m7g.large × 2 | db.t4g.micro Multi-AZ | 없음 | k6, EC2 c6i.xlarge × 3 | `9ad3494` | 1회 |
| [TS-037](docs/troubleshooting/TS-037-rds-redis-failover-app-behavior.md)·[IMP-021](docs/improvements/IMP-021-cluster-autoscaler-node-scaling.md) 페일오버·노드 확장 | 2026-08-26 | m6i.large | 기록 없음 | 기록 없음 | 기록 없음 | 클러스터 안 k6(25·400 rps) | 기록 없음 | 각 1회 |

이후 대기열 보강(TS-043~045)은 통합 테스트로 검증했고, Kafka 제거([ADR-028](docs/decisions/ADR-028-kafka-reevaluation.md))도 IMP-031 측정 이후의 변경입니다. 그래서 Kafka 없는 분리 구조(B)의 기준선은 [IMP-034](docs/improvements/IMP-034-bprime-queue-resources-g1.md)에서, 운영 기본값의 10만 진입은 IMP-035에서 같은 판정 기준으로 다시 쟀습니다.

## 설계 결정과 고르지 않은 선택지

| 고른 것 | 고르지 않은 것 | 이유 | 근거 |
|---|---|---|---|
| 같은 이미지의 대기열 전용 Deployment | 통합 구조 유지 / 서비스로 쪼개기 | 폭증 중 예매 p95가 통합 1.6~2.6초, 분리 184~272ms(각 2회, 운영 관점 판정 — 분리만의 효과는 떼어 내지 않음). 노드 비용 +15~28%(계산)를 감수 | [ADR-027](docs/decisions/ADR-027-queue-dedicated-deployment.md) · [IMP-033](docs/improvements/IMP-033-queue-split-ab.md) |
| 대기열 상태는 폴링(`retryAfterMs`) | 대기열 SSE | 대기자 전원의 SSE 연결이 Pod 메모리 1Gi에서 약 3,300~3,500개 시점에 OOM | [ADR-023](docs/decisions/ADR-023-burst-entry-and-waiting-two-axes.md) |
| Outbox + Redis Pub/Sub | Kafka | 아웃박스 이벤트가 `order.paid` 하나뿐이라 브로커 3개 운영은 지금 요구에 과하다고 판단 | [ADR-028](docs/decisions/ADR-028-kafka-reevaluation.md) |
| `preStop` 25s | `deregistration_delay` 늘리기 | 실패가 타깃 해제 **전** 구간에서 났고 애플리케이션 5xx는 0 | [TS-035](docs/troubleshooting/TS-035-rolling-deregistration-race.md) |
| AZ별 NAT 3개 | NAT 1개(−$0.128/h, 계산) | 단일 NAT면 그 AZ 장애가 정상 AZ의 결제까지 막아 3 AZ 격리 주장이 성립하지 않음 | [ADR-012](docs/decisions/ADR-012-3az-eks-infra-cost-control.md) · [IMP-040](docs/improvements/IMP-040-session-cost-breakdown.md) |
| Tomcat 스레드 200 | 400 | 하위 의존성이 느린 조건에서 400으로 늘려도 개선 없음(로컬) | [IMP-036](docs/improvements/IMP-036-local-threads-and-booking-burst.md) |
| 평시 노드 5대 유지 | 요청량 낮추기 / 평시 합치기 / 평시 api 2개 | 요청량 낮추기는 절감 1대가 api·대기열 동거에서 나왔고 동거가 경합을 만들어 기각. 평시 합치기는 평시 가정 부하(폴링 300/s)에서도 경합 판정(같은 조건끼리 편차가 커 약한 근거)이라 보류. 평시 api 2개는 노드 절감이 2회 중 1회, 예매 영향 판단 불가라 보류(모두 로컬·시뮬레이션) | [IMP-038](docs/improvements/IMP-038-api-queue-colocation-contention.md) · [IMP-039](docs/improvements/IMP-039-peace-consolidate-open-separate.md) · [IMP-041](docs/improvements/IMP-041-peace-api-two.md) |
| CA 축소 대기 10분 | 5분 | 통합 구조 run 기준 계산으로 run당 약 $0.08을 아끼는 대신 폭증 직전의 여유 노드를 잃음(추정) | [IMP-032](docs/improvements/IMP-032-autoscaling-node-hours.md) §10 |

## 핵심 설계

- **대기열**: Redis Lua 스크립트로 토큰 발급·입장 승격을 원자적으로 처리합니다. 좌석 선점·이탈은 토큰을 발급받은 회원만 할 수 있고, 대기 토큰은 폴링하는 동안 수명이 연장됩니다.
- **좌석과 주문**: PostgreSQL 조건부 `UPDATE`와 영향 행 수 검증으로 중복 선점·초과판매를 막습니다.
- **결제 상태 전이**: 주문·결제·좌석·hold 상태를 명시적으로 전이하고 멱등성 키를 사용합니다.
- **이벤트 전달**: 비즈니스 트랜잭션과 Outbox 기록을 함께 저장하고, 릴레이가 Redis Pub/Sub(`sse:order`)으로 발행해 SSE로 전달합니다.
- **실시간 반영**: 대기열은 서버가 다음 조회 시점(`retryAfterMs`)을 정하는 폴링으로, 좌석·주문 상태는 SSE와 재조회 보정으로 사용자 화면에 반영합니다.
- **용량과 확장**: 예매 api의 CPU 요청량 1100m는 통합 구조에서 진입 요청당 처리 비용(실측)으로 계산한 값입니다([ADR-025](docs/decisions/ADR-025-api-requests-hpa-policy.md)). 분리 구조로 바꾼 뒤 근거를 다시 검토해 값은 유지했습니다 — 예매 api는 1분 평균으로는 최대 0.03~0.08코어(AWS 측정 재분석)지만 예매 순간 1~2초 동안 코어 2개 가까이 쓰고(로컬 1회), 요청량을 낮춰 노드를 합치면 대기열과 같은 노드를 나눠 폴링·예매가 함께 느려졌습니다(로컬 조건당 2회). 대기열의 1100m는 낮출 후보가 있으나 클라우드 검증 전이라 그대로 둡니다([설정값 근거](docs/deployment/optimization-targets.md)). HPA와 Cluster Autoscaler로 Pod·Node를 늘리고 줄이며(분리 구조: api 3 고정 + 대기열 2~6), 대기열 Pod 첫 증가에 40~70초, 노드 추가에 100~150초가 걸려(AWS 측정 재분석) 10초 오픈 폭주에는 반응하지 못한다고 보고(추론) 오픈 전 사전 확장(대기열 2 → 6)은 Git PR로 켜고 끕니다.
- **외부 데이터 분리**: KOPIS 호출은 요청 경로가 아닌 배치 동기화 경로에서 처리합니다.

## 백엔드 아키텍처

백엔드는 Controller → Service → Repository/Gateway 경계로 구성됩니다. 위험 도메인에서는 일반적인 엔티티 저장보다 상태 조건을 포함한 명시적 전이를 우선합니다.

| 영역 | 책임 | 주요 구성 |
|---|---|---|
| 인증 | 회원가입·로그인·토큰 재발급·소셜 로그인 | JWT Access/Refresh, Redis rotation, Kakao/Naver OAuth2 |
| 공연 | KOPIS 동기화·검색·상세·인기 지표 | 스케줄 기반 동기화 작업, PostgreSQL |
| 대기열 | 발급·순번 조회·입장 승격·만료 | Redis, Lua, 폴링(`retryAfterMs`) |
| 예매 | 좌석 hold·주문·결제·환불 | JPA, QueryDSL, 조건부 UPDATE, 멱등성 |
| 이벤트 | 주문 완료 이벤트와 실패 격리 | Transactional Outbox, Redis Pub/Sub |
| 운영 | 공연·주문·아웃박스·알림 관리 | Spring Security RBAC, 관리자 API |

## 인프라 아키텍처

![FlowTicket AWS 인프라](docs/assets/architecture-infra.png)

<sub>원본: [`docs/assets/architecture-infra.drawio`](docs/assets/architecture-infra.drawio) — diagrams.net에서 열어 고칠 수 있습니다.</sub>

배포는 **push가 아니라 pull**이다. CI는 이미지를 ECR에 올리고 매니페스트의 태그를 Git에
커밋하는 데서 멈추며(`.github/workflows/image.yml`), 클러스터에 적용하는 것은 Argo CD다.
`Terraform=인프라 / Argo CD=앱`으로 소유를 나눈다 — 근거는
[ADR-009](docs/decisions/ADR-009-gitops-cd-argocd.md).

- 로컬 개발은 Docker Compose로 PostgreSQL·Redis를 띄우고, 통합 환경(`infra/docker-compose.prod.yml`)은 여기에 API·web을 더합니다.
- 클러스터 구성은 Kubernetes manifests와 Terraform을 사용하며, API는 health probe, graceful shutdown(`preStop`), HPA(분리 구조: api 3 고정·대기열 2~6) + Cluster Autoscaler, PDB 설정을 가지고, web은 사전 확장 때 API에 자리를 내주도록 낮은 PriorityClass를 둡니다.
- 대기열 진입·상태 조회는 ALB가 Next.js를 거치지 않고 대기열 전용 Pod(api와 같은 이미지, 별도 Deployment)로 바로 보냅니다([ADR-024](docs/decisions/ADR-024-queue-path-next-hop-bypass.md) · [ADR-027](docs/decisions/ADR-027-queue-dedicated-deployment.md)). 예매하기 → 대기열 화면 이동도 web 서버 렌더 없이 정적 경로로 처리합니다(로컬·CI 검증, [TS-053](docs/troubleshooting/TS-053-queue-nav-still-through-web.md)). 오픈 순간 공연 페이지의 재조회(창 포커스·새로고침)는 아직 web을 거치고, 10만 시험 범위 밖입니다.
- 비밀값은 환경변수와 External Secrets 경로로 주입하며 코드에 저장하지 않습니다.
- 배포·운영 문서는 [docs/deployment](docs/deployment/_index.md)와 [infra](infra)에서 관리합니다.

## 비용

클러스터는 측정할 때만 올리고 끝나면 철거합니다. 금액은 서울 리전 공개 가격표(온디맨드 정가)로 계산한 값입니다.

| 상태 | 시간당 (계산) | 근거 |
|---|---|---|
| 평시(노드 5대) + 부하 발생기 3대 | 약 $2.06 — 노드 29%·발생기 28%·Redis 19%·NAT 9% | [IMP-040](docs/improvements/IMP-040-session-cost-breakdown.md) |
| 사전 확장(노드 9대) + 발생기 3대 | 약 $2.55 | [IMP-040](docs/improvements/IMP-040-session-cost-breakdown.md) |
| 평시, 발생기 0대 | 약 $1.46 | [IMP-040](docs/improvements/IMP-040-session-cost-breakdown.md) |

- 자동 확장은 통합 구조 판정 run에서 노드 9 고정 대비 노드-시간을 11~13% 덜 썼습니다(계산, [IMP-032](docs/improvements/IMP-032-autoscaling-node-hours.md)). 분리 구조 10만 진입 run([IMP-035](docs/improvements/IMP-035-b-config-100k-entry.md))의 같은 계산은 하지 않았습니다(분리 구조의 노드 비용 차이는 [IMP-033](docs/improvements/IMP-033-queue-split-ab.md)에서 계산).
- 세션 비용은 세션 길이에 비례합니다. 그래서 줄일 곳은 노드 수보다 발생기를 켜 두는 시간과 세션 길이로 정했습니다([IMP-040](docs/improvements/IMP-040-session-cost-breakdown.md) §5).
- 철거는 `scripts/tear-down.sh`가 Terraform 밖 리소스(Ingress·ALB, EBS, ENI)부터 치우고 잔여를 점검합니다(`--audit-only`는 점검만). ECR·ACM·Route53·tfstate 같은 bootstrap 리소스는 남깁니다.
- AWS Budgets(월 $50, 4단계 알림)와 Cost Anomaly Detection을 켜 두었습니다([ADR-012](docs/decisions/ADR-012-3az-eks-infra-cost-control.md)).

## 실행하기

### 로컬 — AWS 비용 없음

빌드된 이미지로 PostgreSQL·Redis·API·web 전체를 띄웁니다. 처음 빌드는 몇 분 걸립니다.

```bash
docker compose -f infra/docker-compose.prod.yml up --build -d
```

KOPIS 키가 없으면 공연 동기화가 되지 않으므로, 판매 중인 공연 1개(좌석 100석)를 시드로 넣습니다.

```bash
docker compose -f infra/docker-compose.prod.yml exec -T postgres psql -U flowticket -d flowticket < e2e/ci/seed.sql
```

- 브라우저에서 `http://localhost:3000`을 엽니다(API는 `8080`). 포트가 겹치면 `WEB_PORT`·`API_PORT` 환경변수로 바꿉니다.
- 회원가입의 휴대폰 인증은 로컬에서 mock입니다. 고정 코드 `123456`을 넣으면 됩니다(`application.yml`의 `auth.phone`).
- 결제는 기본값이 mock 게이트웨이(`PAYMENT_GATEWAY=mock`)입니다.
- 정리: `docker compose -f infra/docker-compose.prod.yml down -v`

### AWS — 비용 발생

세션 시간당 약 $2(계산, [비용](#비용))가 듭니다. 도메인·ECR·ACM·tfstate 같은 계정별 bootstrap이 먼저 필요합니다([terraform-design.md](docs/deployment/terraform-design.md)). 처음 한 번은 `environments/demo`에서 `terraform.tfvars`(관리자 IP — 기본값 없음)와 `backend.hcl`(state-bootstrap 출력)을 만들고 `terraform init -backend-config=backend.hcl`로 초기화합니다([platform README](infra/terraform/platform/README.md) · [`backend.hcl.example`](infra/terraform/platform/environments/demo/backend.hcl.example)).

```bash
terraform -chdir=infra/terraform/platform/environments/demo apply -var-file=loadtest.tfvars
```

```bash
bash scripts/bring-up.sh
```

```bash
bash scripts/tear-down.sh
```

`bring-up.sh`는 `terraform apply` 뒤의 절차(AWS Load Balancer Controller·Cluster Autoscaler·kube-prometheus-stack·Argo CD 설치, External Secrets 부트스트랩, 워크로드 배포, Route53 갱신, 데이터 시딩)를 한 번에 합니다. `terraform apply`는 비용이 발생하는 유일한 단계라 일부러 스크립트에 넣지 않았습니다.

## 기술 스택

| 영역 | 기술 |
|---|---|
| Backend | Java 17 (Temurin), Spring Boot 3.3.x, Gradle 8, Spring Web, Spring Security, Spring Data JPA, QueryDSL 5 (Jakarta) |
| Data & Messaging | PostgreSQL 16, Flyway, Redis (Lettuce, Pub/Sub) — 로컬·CI `7.4` / ElastiCache `7.1` |
| Authentication | JWT Access/Refresh, OAuth2 Client, Kakao·Naver |
| Frontend | Node.js 20 LTS, pnpm 9, Next.js 14.2 App Router, TypeScript 5.5 |
| Frontend State & UI | Tailwind CSS 3.4, shadcn/ui, TanStack Query 5, Zustand 4, React Hook Form 7, Zod 3 |
| Infrastructure | Docker Compose v2, Kubernetes, Terraform, Argo CD, AWS EKS, HPA, Cluster Autoscaler |
| Observability | Prometheus, Grafana (kube-prometheus-stack), ALB access log |
| Verification | JUnit 5, Testcontainers 1.20, Playwright, k6(EC2 분산 부하 발생기 3대), GitHub Actions |

## 저장소 구조

```text
apps/
  api/                 Spring Boot API, Flyway migration, 도메인·인프라 코드
  web/                 Next.js App Router 사용자·운영 화면
assets/screens/        화면 레퍼런스 이미지
contracts/             enum, API, event, error, stack, layer 계약
docs/
  common/              공통 API·레이아웃·디자인 시스템
  rules/               도메인·백엔드·프론트 규칙
  screens/             기능별 구현 목록과 화면 스펙
  decisions/           ADR 설계 결정
  improvements/        IMP 측정 기반 개선 기록
  troubleshooting/     TS 장애·회고 기록
  deployment/          배포·인프라 문서
  testing/             E2E·성능 검증 규칙
e2e/                   Playwright 크리티컬 플로우
harness/               계약·구조 드리프트 정적 검사
infra/                 Docker Compose, Terraform, k6
k8s/                   Kubernetes base, overlay, monitoring, Argo CD
```

## 품질 검증 방식

하네스는 이 프로젝트의 기능 자체가 아니라, 기능 구현이 계약과 규칙에서 벗어나지 않는지 확인하는 안전망입니다.

| 단계 | 무엇을 확인하나 | 예 |
|---|---|---|
| 계약 (`contracts/`) | enum·API·이벤트·오류 코드·계층 규칙을 YAML 단일 진실원으로 고정 | `events.yaml`에서 구현됨(`implemented`)으로 선언한 이벤트는 실제 발행 코드가 있어야 통과 |
| 정적 하네스 (`npm run harness:check`) | 계약과 코드·매니페스트·문서의 어긋남 | 상태 변경 UPDATE의 `WHERE status` 가드, 파괴적 DDL, `@Autowired` 금지(생성자 주입), SSE 훅의 재동기화 경로, 매니페스트↔앱 설정 |
| 메타테스트 | 하네스가 위반을 실제로 잡는지 | 일부러 만든 위반 fixture 75개가 전부 차단되는지 |
| 단위·통합 테스트 (Testcontainers) | 동시성·상태 전이·Outbox 런타임 정합성 | 실제 PostgreSQL·Redis 컨테이너 |
| 알림 규칙 테스트 (promtool) | 알림이 울려야 할 때 울리고, 아닐 때 울리지 않는지 | 규칙 4개 모두 양성·음성 케이스 |
| E2E (Playwright, CI 풀스택) | 브라우저↔API 경계와 UI 상태 | 프론트·백엔드가 바뀐 PR에서 실행, 실패하면 PR 체크 실패와 trace 업로드 |
| 부하 시험 (k6, 클러스터 세션) | 10만 명 진입·폴링 폭증에서 정합성이 깨지지 않는지 | 판정식을 먼저 적고 run → 사후 정합성 검사 |

하네스의 범위, 명령, 검사 한계는 [docs/HARNESS.md](docs/HARNESS.md)에 정리되어 있습니다.

## 문서 지도

| 목적 | 문서 |
|---|---|
| 기능별 구현·화면 상태 | [docs/screens/_index.md](docs/screens/_index.md) |
| 도메인 불변식 | [docs/rules/domain-rules.md](docs/rules/domain-rules.md) |
| API·이벤트·enum 계약 | [contracts](contracts) |
| 공통 API·레이아웃·디자인 | [docs/common](docs/common) |
| 설계 결정과 대안 | [docs/decisions](docs/decisions/_index.md) |
| 측정 기반 개선 기록 | [docs/improvements](docs/improvements/_index.md) |
| 10만 명 부하 검증 | [100K 계획서](docs/testing/loadtest-100k-plan.md), [IMP-029](docs/improvements/IMP-029-final-100k-10s-queue.md)~[IMP-031](docs/improvements/IMP-031-integrated-platform-100k.md), [IMP-035](docs/improvements/IMP-035-b-config-100k-entry.md)(대기열 분리 구조) |
| 대기열 분리 비교 | [ADR-027](docs/decisions/ADR-027-queue-dedicated-deployment.md), [IMP-033](docs/improvements/IMP-033-queue-split-ab.md) |
| 설정값 근거와 비용 | [optimization-targets.md](docs/deployment/optimization-targets.md), [IMP-040](docs/improvements/IMP-040-session-cost-breakdown.md) |
| 장애 분석과 재발 방지 | [docs/troubleshooting](docs/troubleshooting/_index.md) |
| 배포·E2E·성능 검증 | [docs/deployment](docs/deployment/_index.md), [docs/testing](docs/testing/e2e-rules.md) |

## 작업 원칙

기능 하나를 **DB 스키마 → API → 프론트 화면 → 통합 검증**까지 끝낸 뒤 다음 기능으로 넘어갑니다. 위험 도메인인 대기열·좌석·결제·환불을 변경할 때는 해당 도메인 규칙과 ADR을 먼저 확인합니다.

상세 작업 규칙은 [AGENTS.md](AGENTS.md), 계약·하네스의 상세 설명은 [docs/HARNESS.md](docs/HARNESS.md)를 참고합니다.
