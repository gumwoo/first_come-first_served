# IMP-046 · Redis 장애 중 조회 유지: readiness에서 Redis 제외 + Redis 명령 2초 상한 + 503 응답(로컬 장애 주입)

- 슬라이스: 횡단(가용성)
- 날짜: 2026-10-24(로컬, 장애 분석 실험실 `infra/triage-lab`의 PostgreSQL·Redis에 실험용 api 컨테이너 4개를 붙임)
- 유형: 정성 + 응답 시간 실측(로컬)
- 관련: [[ADR-030]](결정), [[ADR-016]](블랙리스트 fail-open), [[ADR-027]](대기열 전용 Deployment)
- 벤치 파일: 레포 밖 `artifacts/readiness/`: `before.jsonl`(1차, 변경 전 2개), `outage.jsonl`(본 측정, 4개 동시). 도구 `scripts/resilience/redis-outage-run.sh`·`redis-outage-probe.mjs`

## 1. 상황 (Context)
api readiness에 redis가 있어, 모든 Pod가 함께 쓰는 Redis가 장애면 모든 api Pod가 동시에 Service에서 빠진다. 조회 경로의 Redis 사용은 장애 시 통과·DB 대체로 버티도록 만들어져 있는데([[ADR-016]], 랭킹 대체), readiness가 그 앞에서 트래픽을 막는 구조였다.
readiness에서 Redis를 빼면 조회가 실제로 살아나는지, Redis가 "끊긴" 경우와 "멈춘" 경우를 나눠 쟀다.

## 2. 문제 정의 + 분류
- 계층: 외부 의존성(Redis) 장애 시 애플리케이션 동작, 쿠버네티스 readiness 정책.
- 질문: Redis 장애 중 Redis 없이 처리할 수 있는 요청(공연 목록·상세·좌석, 내 주문)이 응답하는가. Redis가 필요한 요청(대기열 진입, 좌석 선점)은 무엇을 받는가.

## 3. 측정 방법
- 대상: 같은 PostgreSQL·Redis에 붙인 api 컨테이너 4개(코어 2개, 메모리 2GB).

| 인스턴스 | 이미지 | readiness | Redis 명령 상한 | Redis 실패 응답 |
|---|---|---|---|---|
| a | 변경 전(main) | readinessState, db, redis | 60초(Lettuce 기본값) | 500 |
| b | 변경 전 + `MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db` | readinessState, db | 60초 | 500 |
| c | 변경 후 기본값(예매 api) | readinessState, db | 2초 | 503 |
| q | 변경 후 + `HEALTH_READINESS_INCLUDE=readinessState,db,redis`(대기열) | readinessState, db, redis | 2초 | 503 |

- 장애: ① `docker stop`(연결 거부), ② `docker pause`(연결은 열린 채 응답 없음). 넣고 10초 뒤부터 2회씩 잰다. 복구 뒤 1회 더 잰다.
- 요청: 같은 네트워크의 컨테이너에서 인스턴스·엔드포인트를 동시에 부른다(요청마다 75초 상한). `/readyz`, 공연 목록, 공연 상세, 좌석 조회, 내 주문(인증), 대기열 진입(인증), 좌석 선점(인증, 입장 안 한 토큰).
- 1회 측정이다(장애 2종 × 2회 × 인스턴스 4개). 클러스터에서는 재지 않았다.
- Docker Desktop 포트 전달로 호스트에서 부르면 동시 연결이 8개를 넘을 때 30초 뒤 연결이 끊겨(ECONNRESET), 실험실 네트워크 안의 컨테이너에서 불렀다.

## 4. 결과 (로컬 실측)
중지·일시정지 두 장애의 결과가 같았다. 칸은 상태 코드와 응답 시간(4회의 범위)이다.

| 요청 | a 변경 전 | b readiness만 변경 | c 변경 후(예매 api) | q 변경 후(대기열) |
|---|---|---|---|---|
| `/readyz` | 503, 60.0~60.2초 | 200, 0.1초 이하 | 200, 0.1초 이하 | 503, 2.1초 |
| 공연 목록 | 200, 0.1~0.2초 | 200, 0.1~0.3초 | 200, 0.1초 | 200, 0.1초 |
| 좌석 조회 | 200, 0.1~0.3초 | 200, 0.1~0.3초 | 200, 0.1~0.2초 | 200, 0.1초 |
| 공연 상세 | 200, 60.0~60.2초 | 200, 60.0~60.2초 | 200, 2.0~2.2초 | 200, 2.0~2.1초 |
| 내 주문 | 200, 60.0~60.1초 | 200, 60.0~60.1초 | 200, 2.0~2.1초 | 200, 2.0~2.1초 |
| 대기열 진입 | 75초 안에 응답 없음 | 75초 안에 응답 없음 | 503 `SERVICE_UNAVAILABLE`, 4.0~4.1초 | 503, 4.0~4.1초 |
| 좌석 선점 | 75초 안에 응답 없음 | 75초 안에 응답 없음 | 503 `SERVICE_UNAVAILABLE`, 4.0~4.1초 | 503, 4.1초 |

- Redis가 정상일 때와 복구 뒤에는 네 인스턴스 모두 `/readyz` 200, 조회 200, 대기열 진입 200, 좌석 선점 403 `QUEUE_NOT_ADMITTED`(입장 안 한 토큰)였다.
- a: Redis 장애 중 `/readyz`가 503이다. 쿠버네티스 probe(시간 제한 1초)는 그 전에 실패하므로, 모든 api Pod가 Service에서 빠져 공연 목록처럼 Redis를 쓰지 않는 요청도 ALB에서 끝난다(쿠버네티스 동작에서 미루어 본 것).
- b: readiness는 200이지만 공연 상세·내 주문이 60초 걸렸다. 블랙리스트 조회·조회수 기록이 Lettuce 기본 명령 상한(60초)을 다 기다린 뒤에야 통과·무시로 넘어갔다. readiness만 바꾸면 조회가 살아나지 않는다.
- c: 공연 상세·내 주문이 약 2초(명령 상한 1회), 대기열 진입·좌석 선점이 약 4초 뒤 503이다. 4초는 Redis 명령 두 번이 각각 2초씩 시간 초과된 것으로 보인다(추론).
- q: `/readyz`가 2.1초 걸려 503이다. probe 시간 제한(1초)을 넘으므로 쿠버네티스에서는 시간 초과로 실패해 Service에서 빠진다(미루어 본 것).
- 공연 목록·좌석 조회는 네 인스턴스 모두 빨랐다. 이 두 요청은 인증 없이 불러 블랙리스트를 조회하지 않고, 좌석 캐시는 기본값으로 꺼져 있어(`seat.map-cache-ttl-ms: 0`) Redis를 쓰지 않는다.

## 5. 조치
- `spring.data.redis.timeout: ${REDIS_COMMAND_TIMEOUT:2s}`(기본 60초 → 2초).
- 예매 api readiness: `readinessState, db`. 대기열 전용 Deployment: `HEALTH_READINESS_INCLUDE=readinessState,db,redis`(하네스 k8s ⑩-b 허용 목록 + ⑩-c, 위반 fixture `k8s-queue-readiness-no-redis`).
- Redis 연결 실패·명령 시간 초과 → 503 `SERVICE_UNAVAILABLE`(`GlobalExceptionHandler`, `contracts/error-codes.yaml`).
- 특정 Pod만 Redis에 못 붙는 경우는 기존 알림 `FlowticketBlacklistCheckFailing`이 잡는다.

## 6. 트레이드오프 / 다음 개선
- 측정 조건: 로컬 1회, 요청 하나씩이다. 부하 중 Redis 장애(스레드가 실제로 얼마나 묶이는지)와 클러스터에서의 Service 제외는 재지 않았다.
- 2초 상한은 같은 연결을 쓰는 대기열 Lua·ShedLock·좌석 알림 구독에도 적용된다. 정상 꼬리 지연이 2초를 넘는 순간이 있으면 정상 요청이 503이 된다. 폭증 중 꼬리 지연은 측정이 없어([[IMP-027]]은 평균 최대 39.9ms), 다음 클러스터 부하 시험에서 시간 초과 수를 함께 본다.
- 대기열 Pod의 Redis readiness 검사도 같은 연결을 써서, 폭증 중 Redis 응답이 1초를 넘으면 정상인데도 Pod가 빠질 수 있다([[IMP-024]] §8).
- 장애 중 조회는 2초씩 걸린다. 블랙리스트 조회에 더 짧은 상한을 두거나 연결이 끊긴 동안 명령을 바로 거부하면(Lettuce `disconnectedBehavior`) 줄일 수 있다. 이번에는 바꾸지 않았다.

## 7. 배운 점 (면접 답변용 한 줄)
readiness에서 공유 의존성을 빼기 전에 Redis를 실제로 멈춰 보니, readiness만 바꾸면 조회가 60초 명령 상한에 묶여 살아나지 않는다는 것을 확인했고, 명령 상한·readiness·503 응답을 함께 바꿔 Redis 장애 중에도 조회가 약 2초 안에 응답하게 했다.
