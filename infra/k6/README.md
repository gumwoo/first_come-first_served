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
| `read-load.js` | ① 조회 API 부하(목록/좌석맵/상세) — 무릎 탐색 | 지금 |
| (예정) `hold-contention.js` | ② 매진 경합 — 잔여 N석 동시 HOLD, 초과판매 0 | 다음 |
| (예정) `spike-queue.js` | ③ 스파이크 — 오픈 순간 대기열 진입, over-admit 0 | 다음 |
| (예정) `failure-dlq.js` | ④ 실패주입 → DLQ (Kafka 필요) | **S07 이후** |

## ① 조회 부하 — 무릎 찾기
VU를 올려가며 각각 실행해 RPS 정체·p95/p99 급등 지점을 본다:
```
k6 run -e K6_VUS=300  --summary-export=benchmarks/read-load-vu300.json  infra/k6/read-load.js
k6 run -e K6_VUS=500  --summary-export=benchmarks/read-load-vu500.json  infra/k6/read-load.js
k6 run -e K6_VUS=750  --summary-export=benchmarks/read-load-vu750.json  infra/k6/read-load.js
k6 run -e K6_VUS=1000 --summary-export=benchmarks/read-load-vu1000.json infra/k6/read-load.js
```
(대상 서버가 다르면 `-e K6_BASE_URL=http://host:8080`)

각 실행 요약(RPS·p95·p99·실패율)을 공유하면 무릎 구간을 함께 판단하고,
병목을 규명(EXPLAIN/커넥션풀/N+1)한 뒤 최적화 1건 → 재측정으로 성능 IMP를 만든다.

## 해석 원칙 (정직성)
- 로컬 단일 인스턴스 = k6와 서버가 자원 공유 → **절대 처리량이 아니라 추세**로 해석.
- 수치는 실측만 기록. before/after는 최적화 전후 동일 조건에서.
