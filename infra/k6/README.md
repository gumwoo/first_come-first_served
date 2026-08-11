# 부하테스트 (k6) — S08

선착순 티켓팅의 **성능·정합성을 실부하로 검증**한다. 정적 하네스로 못 잡는 영역이라
[performance-rules.md](../../docs/rules/performance-rules.md)의 목표 임계로 관리한다.

## 목표 임계 (performance-rules)
- 핵심 API p95 ≤ 300ms · 에러율 ≤ 0.5% · 초과판매 0 · (DLQ 재처리는 S07 이후)

## 전제
- 스택 기동: `docker compose up -d`(postgres/redis/kafka) + 백엔드(:8080) 실행.
- 백엔드에 판매중(ON_SALE) 이벤트 + 좌석이 있어야 함(로컬 KOPIS 동기화분 또는 시드).
- k6 설치: `winget install k6` / `choco install k6` / 또는 `docker run --rm -i grafana/k6`.

## 시나리오
| 파일 | 시나리오 | 상태 |
|------|----------|------|
| `read-load.js` | ① 조회 API 부하 — VU 고정(closed model) | 있음 |
| `read-load-rate.js` | ①' 조회 API 부하 — **도착률 고정(open model)**. 무릎 탐색은 이쪽이 정확하다 | 있음 |
| (예정) `hold-contention.js` | ② 매진 경합 — 잔여 N석 동시 HOLD, 초과판매 0 | 다음 |
| (예정) `spike-queue.js` | ③ 스파이크 — 오픈 순간 대기열 진입, over-admit 0 | 다음 |
| (예정) `failure-dlq.js` | ④ 실패주입 → DLQ (Kafka 필요) | **S07 이후** |

## ⚠️ 옵션 이름에 `K6_` 접두사를 쓰지 않는다

`K6_VUS`·`K6_DURATION` 같은 이름은 **k6 자신의 환경변수 옵션**이라 스크립트의 `scenarios`를
통째로 덮어쓴다. 2026-08-11 측정에서 실제로 당했다 — `constant-arrival-rate`가 사라지고
`vus_max=1`로 돌아 100 rps를 요청했는데 25 rps만 나왔다.

```
level=warning msg="env level configuration overrode scenarios configuration entirely"
scenarios: 1 scenario, 1 max VUs
```

경고가 뜨지만 실행은 되므로 **결과를 보기 전에는 알아채기 어렵다.** 그래서 옵션을
`VUS`·`RATE`·`RUN_FOR`로 쓴다.

## ① 조회 부하 — 무릎 찾기

**도착률 고정(open model)을 우선한다.** VU 고정은 서버가 느려지면 한 VU의 사이클이 길어져
부하가 스스로 줄어들기 때문에, 포화점을 지나쳐도 그래프가 완만해 보인다.

```
k6 run -e K6_BASE_URL=... -e RATE=100 -e RUN_FOR=90s infra/k6/read-load-rate.js
k6 run -e K6_BASE_URL=... -e RATE=200 -e RUN_FOR=90s infra/k6/read-load-rate.js
...
```

VU 고정은 보조로 쓴다:
```
k6 run -e VUS=300  --summary-export=benchmarks/read-load-vu300.json  infra/k6/read-load.js
k6 run -e VUS=500  --summary-export=benchmarks/read-load-vu500.json  infra/k6/read-load.js
k6 run -e VUS=750  --summary-export=benchmarks/read-load-vu750.json  infra/k6/read-load.js
k6 run -e VUS=1000 --summary-export=benchmarks/read-load-vu1000.json infra/k6/read-load.js
```
(대상 서버가 다르면 `-e K6_BASE_URL=http://host:8080`)

각 실행 요약(RPS·p95·p99·실패율)을 공유하면 무릎 구간을 함께 판단하고,
병목을 규명(EXPLAIN/커넥션풀/N+1)한 뒤 최적화 1건 → 재측정으로 성능 IMP를 만든다.

## 해석 원칙 (정직성)
- 로컬 단일 인스턴스 = k6와 서버가 자원 공유 → **절대 처리량이 아니라 추세**로 해석.
- 수치는 실측만 기록. before/after는 최적화 전후 동일 조건에서.

## 실측 기록

| 위치 | 내용 |
|---|---|
| `benchmarks/asis-web2pod/` | 2026-08-11 AS-IS(web 2파드 고정). capacity knee 450~600 rps, 첫 실패 지점 web 티어 |
| `benchmarks/tobe-web-hpa/` | 2026-08-11 TO-BE(web HPA 2~4 + api max 7). 600 rps에서 실패 4.06%→0%, p95 10,006→123ms |
