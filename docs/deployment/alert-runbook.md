# 알림 대응 runbook

알림을 받았을 때 처음 볼 것을 알림별로 모은다. 알림 설명(Slack 본문)에는 첫 확인을 한 줄로 넣고, 이 문서는 그 순서와 다음 문서를 적는다.

- 알림 출처
  - Prometheus 규칙 4개: `k8s/monitoring/prometheusrule-flowticket.yaml`. Alertmanager가 Slack으로 보낸다.
  - ALB CloudWatch 알람 3종: `infra/terraform/platform/environments/demo-alarms/main.tf`. SNS와 Lambda를 거쳐 같은 Slack 채널로 온다.
- 대시보드: Grafana "FlowTicket: API / 아웃박스 / 런타임"(`k8s/monitoring/dashboard-flowticket.yaml`). 접속은 `kubectl port-forward`로 한다.
- 알림은 확인을 요청할 뿐이다. 되돌리기는 Git에서 한다([[ADR-009]], ArgoCD가 Git을 따라간다).

## 공통으로 먼저 볼 것
1. 직전에 바뀐 것이 있는가.
   - `kubectl -n argocd get application flowticket`: 동기화 상태와 리비전
   - `kubectl -n flowticket get pods`: AGE(최근 교체), RESTARTS(재시작)
2. 같은 시각에 다른 알림도 울렸는가. ALB 알람과 앱 알림이 함께 오면 어느 쪽이 먼저인지 본다.

## FlowticketHighServerErrorRate
API 5xx 비율이 5분 평균 5%를 넘고(초당 0.1건 이상) 2분 지속됐다. 사용자가 이미 실패를 보고 있다.

1. 직전 배포·Pod 교체가 있었는지 본다(공통 1). 배포 직후라면 그 변경을 먼저 의심하고, 되돌릴지 정한다.
2. 대시보드 "엔드포인트별 5xx (상위 5)"에서 한 경로에 몰렸는지, 전체에 퍼졌는지 본다.
   - 한 경로: 그 기능의 최근 변경과 로그(`kubectl -n flowticket logs deploy/flowticket-api --since=10m`)
   - 전체: 의존성 쪽(3)
3. 의존성을 본다.
   - "DB 커넥션 (active / pending)": pending이 계속 서 있으면 DB나 커넥션 풀 쪽이다.
   - "Redis 명령 평균 완료 시간": 오르면 Redis 쪽이다. Redis 연결 실패·명령 시간 초과(2초)는 503으로 응답한다([[ADR-030]]).
   - `FlowticketBlacklistCheckFailing`이 함께 울리면 Redis를 먼저 본다([[TS-037]] 페일오버 중 앱 동작).

## FlowticketOutboxDeadRows
재시도로 풀리지 않아 DEAD로 격리된 아웃박스 행이 있다. 같은 aggregate의 후속 이벤트가 보류된다.

1. `/admin/outbox`에서 그 행의 payload와 lastError를 본다.
2. 원인이 풀렸으면 재발행, 다시 보내면 안 되는 행이면 폐기한다.
- 상세: [[TS-032]]

## FlowticketOutboxBacklog
미발행(PENDING) 행이 10분째 500건을 넘는다. 발행 대상(Redis) 장애나 명령 시간 초과가 이어지고 있을 가능성이 높다.

1. 대시보드 "미발행 최장 대기"가 계속 늘면 릴레이가 뒤처지는 중이다.
2. 릴레이 로그(`[outbox] 발행 실패`)와 행의 attempts·lastError를 본다.
3. Redis 상태를 본다. Redis가 돌아오면 적체는 스스로 줄어든다.
- 상세: [[IMP-011]], [[ADR-028]]

## FlowticketBlacklistCheckFailing
토큰 블랙리스트 조회가 5분째 실패해 fail-open으로 통과시키는 중이다. 이 동안 로그아웃된 access token이 남은 TTL(최대 30분) 동안 유효하다.

1. Redis 자체 장애인지 본다. 다른 Pod도 같이 실패하면 Redis 쪽이다.
2. 특정 Pod만 실패하면 그 Pod의 Redis 연결 문제다. readiness는 이 경우를 잡지 않아 이 알림이 신호다([[ADR-030]] 결정 5).
- 상세: [[ADR-016]]

## flowticket-alb-elb-5xx
ALB가 대상(Pod)에 닿지 못해 스스로 만든 5xx(502·503·504)가 1분에 기준 건수 이상이다. 앱은 이 요청을 보지 못해 앱 5xx 알림에는 나타나지 않는다.

1. 같은 시각에 롤링 배포 중이었는지 본다(공통 1). 롤링 중 502는 등록 해제 전파와 Pod 종료 순서 문제였다([[TS-035]]).
2. `flowticket-alb-no-healthy` 알람도 울렸으면 그 서비스로 가는 요청이 503으로 끝나는 중이다. 그 알람 절차를 먼저 따른다.
3. 둘 다 아니면 Pod 재시작을 본다. `kubectl -n flowticket describe pod <pod>`의 Last State(OOMKilled 등)와 이벤트.

## flowticket-alb-target-connection-errors
ALB가 대상 Pod와 연결을 맺지 못했다. 1건이어도 울린다.

1. 롤링 배포 중이었는지 본다. 종료 중인 Pod로 보낸 요청이면 [[TS-035]]의 preStop(web 25초)과 grace를 확인한다.
2. 롤링이 아니면 Pod 재시작·OOM을 본다(`kubectl -n flowticket describe pod <pod>`).

## flowticket-alb-no-healthy
알람 이름 끝이 서비스다(`api`, `api-queue`, `web`). 그 대상 그룹의 정상 대상이 2분 동안 0이었고, 그 서비스로 가는 요청은 ALB가 503으로 끝낸다.

1. `kubectl -n flowticket get pods`의 READY 열과 `kubectl -n flowticket describe pod <pod>`의 Readiness probe 실패 이벤트를 본다.
2. 서비스별 헬스체크가 무엇에 달렸는지 보고 원인을 좁힌다([[ADR-030]]).
   - `api`: `/readyz` = readinessState, db. DB 연결을 먼저 본다.
   - `api-queue`(대기열 전용 Deployment): `/readyz` = readinessState, db, redis. Redis가 장애면 대기열 대상만 0이 된다.
   - `web`: `/`.
3. Pod는 정상인데 알람만 울리면 대상 그룹이 다시 만들어진 경우다(Service·헬스체크 변경). 옛 대상 그룹 알람은 데이터가 끊겨 울리므로 `bash scripts/alb-alarms.sh apply`를 다시 한다.
