# 장애 분석 도구용 로컬 실험실

원인을 알고 주입한 예매 묶음 run을 만든다. 장애 초기 분석 도구(`scripts/triage`)의 입력과 평가 사례로 쓴다.
구성은 로컬 실험 L7([IMP-043](../../docs/improvements/IMP-043-booking-burst-connection-wait-cause.md))과 같다.

| 컨테이너 | 고정 코어(기본) | 비고 |
|---|---|---|
| api(예매) | 2·3 | 풀 5, 포트 18080 |
| queue(대기열 전용) | 8·9 | 풀 3, 포트 18081 |
| PostgreSQL 16 | 6·7 | `track_wal_io_timing` 켬 |
| Redis 7.4 | 4 | |
| k6 | 10·11 | `infra/k6/booking-e2e.js`(입장자 100명 동시 예매) |
| 디스크 프로브 | 14 | 측정 대상과 분리된 동기 쓰기 지연 |
| cgroup 수집 | 14 | api 컨테이너 CPU 통계(할당량에 막힌 주기 수), 호스트 cgroup 이름공간에서 읽음 |

## 사용법
```bash
bash infra/triage-lab/lab.sh up            # 빌드·기동·시드(공연 80개·사용자 1,200명)·토큰·예열 1회
bash infra/triage-lab/lab.sh run redis 1   # 주입 → 예매 묶음 → 수집 → 해제, run id 출력
bash infra/triage-lab/lab.sh tokens        # 토큰만 다시 발급(유효 2시간, run 약 90초)
bash infra/triage-lab/lab.sh down
```
- run마다 선점 기록이 없는 공연을 DB에서 골라 쓴다. `up`을 다시 해도 쓴 공연을 고르지 않는다. 결과는 `artifacts/triage-lab/runs/<id>/`(git 밖)에 남는다.
- 원인 라벨은 run 디렉터리 밖 `artifacts/triage-lab/labels.jsonl`에만 쓴다(강도 번호, 실제 주입값, 방법). 분석 도구가 라벨을 보지 않게 하기 위해서다.
- 측정용 psql은 PostgreSQL 컨테이너 밖(프로브 코어)에서 접속한다. db 주입으로 줄인 CPU를 측정이 나눠 쓰지 않게 하기 위해서다.
- 코어 번호는 `LAB_API_CPUS`·`LAB_PG_CPUS`·`LAB_REDIS_CPUS`·`LAB_QUEUE_CPUS`·`LAB_K6_CPUS`·`LAB_PROBE_CPUS`로 바꾼다(`2,3`·`2-3` 표기).
- 호스트 포트가 다른 컨테이너와 겹치면 `LAB_API_PORT`·`LAB_QUEUE_PORT`로 바꾼다(lab.sh·sampler.mjs·compose.yml이 같은 값을 쓴다).

## 주입하는 장애

| fault | 방법 | 강도 1 / 2 | 의도한 증상 |
|---|---|---|---|
| redis | Redis 컨테이너 네트워크 지연(`tc netem`, netshoot 보조 컨테이너) | 30 / 60ms | Redis 명령 완료 지연, Tomcat 바쁜 스레드 증가 |
| db | PostgreSQL 컨테이너 CPU 할당량 | 0.05 / 0.03코어 | PostgreSQL 활성 세션 증가, 커넥션 점유·획득 대기 증가 |
| cpu | 예매 api 컨테이너 CPU 할당량 | 0.4 / 0.25코어 | 커넥션을 쥔 스레드가 CPU를 못 받아 점유 증가, 지표 응답 지연 |
| dbnet | PostgreSQL 컨테이너 네트워크 지연(`tc netem`) | 3 / 10ms | DB는 한가한데 쿼리 왕복마다 늦음. 트랜잭션 내 유휴 세션·커넥션 점유 증가 |
| none | 주입 없음 | | 대조군 |

dbnet은 규칙 분류기의 갈래를 만들 때 없던 장애다. 규칙이 모르는 장애에서 규칙·AI가 어떻게 답하는지 보려고 둔다([IMP-051](../../docs/improvements/IMP-051-triage-eval-out-of-rules.md)).
측정용 psql도 같은 네트워크로 접속하므로 PostgreSQL 지표 표본의 응답도 같이 늦는다.
강도는 개발 run(2026-10-28, `artifacts/triage-lab/dev-v5/`)으로 정했다: 3ms에서 예매 p95 중앙값 2,352ms, 10ms에서 3,050ms(같은 시기 대조군 1,162ms). 두 run 모두 규칙은 커넥션 점유 갈래로 app_cpu였다.
평가 run(2026-10-28, 각 3회)에서는 3ms의 예매 p95 중앙값이 844~1,144ms로 이상 판단 기준(1,500ms) 아래였고, 10ms는 2,370~2,964ms였다.

할당량 해제는 `--cpus`를 고정 코어 수로 되돌리는 방식이다. `docker update --cpus 0`은 제한을 풀지 않는다.

## CPU 할당량에 막힌 정도
CPU 할당량으로 주입한 장애에서는 api CPU 사용량이 할당량(0.4·0.25코어) 아래로 낮게 찍힌다. 사용량만 보면 CPU에 여유가 있는 것처럼 보여,
1차 평가에서 AI가 CPU 장애 6회를 모두 다른 원인으로 판정했다([IMP-044](../../docs/improvements/IMP-044-triage-rule-vs-ai-eval.md)).
그래서 api 컨테이너의 cgroup `cpu.stat`(`nr_periods`·`nr_throttled`)을 1초마다 읽어(`cgroup-sampler.sh`) 할당량에 막힌 주기 비율을 요약에 넣는다.
컨테이너 안에서 읽으면 그 컨테이너의 할당량에 같이 묶이므로, 호스트 cgroup 이름공간의 별도 컨테이너에서 읽는다.
개발용 run에서 이 비율은 정상 0~0.1, CPU 장애 0.75(강도 1)·1.0(강도 2)이었다.

## 측정 환경 판정
호스트 저장소가 느린 run은 원인 분석에서 뺀다([IMP-043](../../docs/improvements/IMP-043-booking-burst-connection-wait-cause.md)).
판정에는 측정 대상과 분리된 디스크 프로브(`dd`로 8KB 동기 쓰기 1회에 걸린 시간, 0.2초마다)를 쓴다. PostgreSQL의 WAL 동기화 시간은
DB가 CPU를 못 받을 때도 부풀어(아래 db 주입에서 1회 44ms) 장애와 측정 환경을 가르지 못한다.

## 주입 확인 (2026-10-21, 유형마다 1회, 로컬 실측)
요약 값은 `scripts/triage/normalize.mjs` 기준(예매 시작 −2초부터 묶음이 끝날 때까지)이다. 이 run들과 아래 강도 시험 run으로 규칙 분류기(`scripts/triage/rules.mjs`)의 기준값을 정했다.

| fault(강도 1) | 예매 p95 중앙값 | Redis 명령 완료 평균 | PostgreSQL 활성 평균 | 커넥션 점유 평균 | 지표 응답 최대 | 디스크 프로브 중앙값 |
|---|---|---|---|---|---|---|
| none | 324ms | 0.34ms | 0.06 | 5.0ms | 376ms | 3ms |
| redis 30ms | 1,702ms | 33.24ms | 0.05 | 21.8ms | 1,572ms | 3ms |
| db 0.05코어 | 9,992ms | 0.27ms | 1.14 | 243.1ms | 10,695ms | 2ms |
| cpu 0.4코어 | 1,890ms | 1.51ms | 0.01 | 33.0ms | 856ms | 2ms |

강도를 정하기 전에 시험한 값(같은 날, 결과는 `artifacts/triage-lab/runs-superseded/`):
- Redis CPU 0.1코어, Redis 지연 3·10ms: Redis 지연은 0.64·3.65·10.6ms로 나타났지만 예매 p95 중앙값은 591~792ms로, 같은 시기 대조군(816ms)과 같은 범위였다.
- PostgreSQL CPU 0.3코어, 같은 코어 경쟁 컨테이너(CPU 가중치 4배): 예매 p95 중앙값 621·403ms, 활성 세션 0.03·0.02(대조군 816ms·0.01).
- 이 시험 run과 주입 확인 run의 라벨 주입값은 실행 기록 순서로 사후 기입했다(라벨에 주입값을 쓰기 전에 만든 run).
- 예매 묶음이 Redis·PostgreSQL에 거는 부하가 작아, 이 정도 제약으로는 사용자 지연이 생기지 않은 것으로 본다(추론).
- api CPU 0.4코어는 두 번 쟀다. 지표 응답 최대가 3,822ms와 856ms로 흔들려, 규칙은 커넥션 점유 평균(44.2·33.0ms)을 주 신호로 쓴다.
