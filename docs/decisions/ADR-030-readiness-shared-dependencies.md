# ADR-030 · readiness와 공유 의존성: 예매 api는 DB만, 대기열은 DB·Redis, Redis 명령은 2초 상한

- 상태: Accepted
- 날짜: 2026-10-24
- 슬라이스: 횡단(가용성)
- 관련: [[ADR-016]](블랙리스트 fail-open), [[ADR-027]](대기열 전용 Deployment), [[IMP-024]](포화 중 probe), [[IMP-046]](Redis 장애 실측)

## 1. 맥락
api의 readiness는 `readinessState, db, redis`였다(S09-0, `docs/deployment/app-changes-for-k8s-kafka.md` C-1). "없으면 요청을 처리할 수 없는 의존성만 넣는다"는 기준이었다.
그 뒤 구조가 바뀌었다.
- 조회 경로의 Redis 사용은 장애 시 버티도록 바뀌었다. 블랙리스트 조회는 실패하면 통과시키고([[ADR-016]]), 인기·실시간 랭킹은 비면 DB 최신순으로 대체하고, 조회수·검색어 기록은 예외를 삼킨다.
  KOPIS 호출은 관리자 동기화·스케줄러에만 있고, 사용자 조회는 DB에 저장한 결과를 읽는다.
- 대기열 진입·상태 조회는 운영 기본값(B)에서 대기열 전용 Deployment가 받는다([[ADR-027]]).

Redis는 모든 api Pod가 함께 쓰는 의존성이다. Redis가 장애면 readiness가 모든 Pod를 동시에 Service에서 빼, 조회처럼 Redis 없이 처리할 수 있는 요청까지 ALB 503으로 끝난다.

로컬에서 Redis를 멈추고(연결 거부) 일시정지(응답 없음)해 보니, readiness만의 문제가 아니었다([[IMP-046]]).
- Redis 명령 시간 상한이 비어 있어 Lettuce 기본값 60초를 따른다. 블랙리스트 조회·랭킹은 60초 뒤에야 통과·대체로 넘어갔고, 공연 상세·내 주문이 60초씩 걸렸다.
- 대기열 진입·좌석 선점은 75초(측정 상한) 안에 응답하지 않았다.
- readiness에서 Redis만 빼면 `/readyz`는 200이 되지만 위 요청은 그대로 60초 이상 걸렸다. Redis를 쓰는 요청이 Tomcat 스레드를 그만큼 쥐고 있다는 뜻이다.

## 2. 결정
1. Redis 명령 시간 상한을 2초로 둔다(`spring.data.redis.timeout: ${REDIS_COMMAND_TIMEOUT:2s}`).
2. 예매 api readiness는 `readinessState, db`로 둔다. 모든 사용자 API가 DB를 쓰므로 DB는 남긴다.
3. 대기열 전용 Deployment는 `HEALTH_READINESS_INCLUDE=readinessState,db,redis`로 redis를 더한다. 대기열 경로는 Redis 없이 처리할 수 없고,
   장애 중에는 Service에서 빠져 ALB가 바로 503을 주는 편이 요청 스레드를 묶지 않는다. 하네스 k8s 규칙 ⑩-b 허용 목록에 이 env를 더하고, ⑩-c가 값을 강제한다.
4. Redis 연결 실패·명령 시간 초과(`RedisConnectionFailureException`, `QueryTimeoutException`)는 500 대신 503 `SERVICE_UNAVAILABLE`로 응답한다.
5. "특정 Pod만 Redis에 못 붙는" 경우는 readiness 대신 알림으로 잡는다. 인증 요청마다 블랙리스트를 조회하므로 기존 `FlowticketBlacklistCheckFailing`(5분 지속)이 이 신호다.

## 3. 근거
- 2초: 정상 Redis 명령은 ms 단위다. 10K/s 진입 폭증에서 Lettuce 평균 지연은 최대 39.9ms였다([[IMP-027]]). 평균만 있어 꼬리 지연은 모른다.
  60초보다 짧아야 장애 중 스레드가 덜 묶이고, 정상 꼬리 지연보다 길어야 정상 요청을 실패로 바꾸지 않는다. 2초는 그 사이의 값이며, 다음 클러스터 부하 시험에서 `lettuce_command_completion` 꼬리와 시간 초과 수로 다시 본다.
- 같은 연결 팩토리를 쓰는 대기열 Lua·ShedLock·좌석 알림 구독에도 2초가 적용된다. 대기열 진입 Lua는 클라이언트가 시간 초과로 끝나도 Redis에서 실행됐을 수 있다.
  이때 사용자는 503을 받고 다시 진입한다. 진입은 이미 살아 있는 토큰이 있으면 그 토큰을 돌려준다(`QueueService.issue`, 1인 1토큰). 이 경로를 시간 초과 조건에서 따로 시험하지는 않았다.
- 대기열에 redis를 남기는 이유는 Redis 전체 장애가 아니라 응답 시간이다. readiness에서 빼면 대기열 Pod는 Ready로 남아 요청마다 최대 2초씩(명령 2회면 약 4초) 스레드를 쥔다.
  대기열은 폭증을 받는 경로라 이 시간이 스레드 포화로 이어질 수 있다(추론). 남기면 probe(시간 제한 1초)가 실패해 Pod가 빠지고 ALB가 503을 준다.
- 대기열 readiness의 Redis 검사도 같은 공유 연결을 쓴다. 폭증 중 Redis 응답이 1초를 넘으면 정상인데도 Pod가 빠질 수 있다([[IMP-024]] §8의 우려와 같다). 2초 상한과 별개로 남는 위험이다.

## 4. 대안
- 둘 다 유지(기존): Redis 장애 중 조회까지 끊긴다. 바꾼 이유 그대로다.
- 둘 다 빼기(대기열 포함): 하네스 허용 목록을 늘리지 않아도 되지만, 대기열 Pod가 장애 중 Ready로 남아 위 근거의 스레드 문제가 생긴다. 하네스는 결정을 검사하는 도구라 이것을 이유로 고르지 않는다.
- Redis만 빼고 시간 상한은 그대로: 실측에서 조회가 60초씩 걸려 효과가 없었다([[IMP-046]]).
- 조회 경로만 짧은 상한의 전용 연결로 분리(아웃박스 발행처럼): 대기열·좌석 선점은 여전히 60초를 물어 스레드가 묶인다. 공통 상한이 더 단순하다.
- DB까지 빼기: 사용자 API가 전부 DB를 쓰므로 얻는 가용성이 없다. DB 헬스 체크는 Hikari 풀에서 커넥션을 빌리므로, 풀이 찬 순간 늦어져 Pod가 빠질 수 있다는 우려는 남는다(추론, 측정하지 않음).

## 5. 결과
- Redis 장애 중 예매 api: `/readyz` 200, 공연 목록·좌석·상세·내 주문은 200(상세·내 주문은 약 2초), 대기열 진입·좌석 선점은 약 4초 뒤 503 `SERVICE_UNAVAILABLE`([[IMP-046]], 로컬).
- 대기열 Pod: `/readyz` 503(약 2초) → probe 실패로 Service에서 빠진다(쿠버네티스 동작에서 미루어 본 것, 클러스터에서 재지 않음).
- TLS 설정이 어긋나 Redis에 못 붙는 경우: 대기열 Pod는 Ready가 되지 않고, 예매 api Pod는 Ready로 뜨되 블랙리스트 조회 실패 알림이 울린다.
- `RedisDefaultFactoryUnchangedIntegrationTest`가 기본 팩토리의 명령 시간 상한을 60초에서 2초로 본다. 아웃박스 발행 전용 연결(3초)과 섞이지 않는다는 검사는 그대로다.
