# IMP-024 · api liveness 실패 허용 3회 → 12회(요청 스레드 포화 중 kubelet 재시작 완화)

- 슬라이스: 인프라(100K 부하 검증 측정 세션)
- 날짜: 2026-10-06
- 유형: 정량(수치)
- 관련 커밋/PR: `3505617`(before, #330 머지) → 이 PR(after)
- 원시 데이터: `artifacts/loadtest/20261005-1440/`(레포에 커밋하지 않는다 — [loadtest-100k-plan §7](../testing/loadtest-100k-plan.md))
- 상태: **before 측정 완료 · after(같은 조건 재시험) 미측정** — 재시험 뒤 §7을 채운다

## 1. 상황 (Context)
[IMP-023](IMP-023-api-memory-sse-connections.md)으로 api 메모리를 2Gi로 올린 뒤 S-10K(계획서 §6 단계 6과 같은 workload)를 다시 걸었다.
OOM은 사라졌지만 api 3개가 모두 run 중 liveness 실패로 재시작해 단계 6을 여전히 판정할 수 없었다.

## 2. 문제 정의 + 분류
진입 부하 중 api의 톰캣 요청 스레드(200)가 막히면, 같은 스레드 풀에서 처리되는 liveness 요청도 시간 초과돼(기본 `timeoutSeconds`
1초) kubelet이 컨테이너를 재시작한다. 재시작은 감사 로그(승격 기록)를 수집 범위 밖으로 밀어 단계 6의 사후 정합성 판정을 막는다.

- 계층: 스레드·커넥션 풀(요청 스레드) + 플랫폼 설정(probe)
- 이 개선의 목표: **재시작을 줄여 단계 6을 판정 가능한 조건으로 만드는 것**(완화). 진입 처리량·지연 개선과 스레드가 막히는 원인 제거는 목표가 아니다.

## 3. 증상 (측정된 증거)
run `step9-10k_constant-r1`(S-10K, 메모리 2Gi, 원시 데이터 — 실측):

| 지표 | 값 |
|---|---|
| api 재시작 | 3개 모두(`kube_pod_container_status_last_terminated_reason` = `Error`) |
| 재시작 원인 | Kubernetes 이벤트 `Liveness probe failed: … context deadline exceeded`(3회) → 15:47:33~35Z `failed liveness probe, will be restarted`(`k8s-events.txt`의 시각은 마지막 발생 시각) |
| 첫 도착 → 재시작 | 약 24~26초(첫 도착 15:47:09Z, `entry-g1.json`) |
| 첫 liveness 실패 → 재시작 | 약 20초(jvd2x·98q54·24q5h 첫 실패 15:47:13·14·15Z → 재시작 15:47:33·34·35Z — 클러스터 이벤트 `firstTimestamp`·`lastTimestamp`, 16:19Z 조회 보존본 `k8s-events-snapshot-161903Z.json`) |
| 진입 지연 p50 / p95 | 10.71s / 23.04s |
| 단계 6 사후 정합성 | 판정 불가(승격 감사 줄 0건, run 중 재시작 3) |

- **요청 스레드 포화가 지표로 잡힌 파드는 3개 중 1개(98q54)뿐이다**(톰캣 바쁜 스레드 200이 15:47:14·24의 두 점). jvd2x·24q5h는 15:47:14에 1이었고
  그 뒤 수집 공백이다(10초 간격 수집) — 두 파드의 liveness 첫 실패(15:47:13·15)는 그 사이다. 두 파드도 같은 이유로 막혔다는 것은 진단 run(§5)에서
  미루어 본 **추론**이다.
- 포화가 얼마나 이어졌는지는 **모른다** — 재시작이 관측을 끊었다. 확인된 것은 하한뿐이다(98q54: 10초 이상).
- before(step6, 1Gi)에도 liveness 시간 초과 이벤트가 있었다(15:17:45·15:18:01Z). 그때는 OOM이 먼저 컨테이너를 끝냈다.

## 4. 가설
- 가설 1: 요청 스레드가 외부 응답을 기다리며 막혀 liveness가 1초 안에 응답하지 못한다.
- 대기 대상 후보: Redis 응답(Lettuce 공유 연결 하나, 진입 경로의 순차 호출), DB 커넥션(Hikari 최대 5), 톰캣 연결 등록 락
  (`server.tomcat.mbeanregistry.enabled: true` — 톰캣 지표 수집용 JMX 등록).

## 5. 검증
같은 workload의 진단 run(`diag-10k_constant-r1`, 판정용 아님)에서 진입 포화 구간에 api 파드마다 SIGQUIT 스레드 덤프를 2회 떴다(실측):

| 파드 | 1회차(약 15:55:52Z) 요청 스레드 200개 | 2회차(약 15:56:04Z) | 결과 |
|---|---|---|---|
| 24q5h | Lettuce 응답 대기 159, **Hikari 커넥션 대기 37**(`QueueService.requireBookable` → `findById`) | **199개 BLOCKED** — 톰캣 `ConnectionHandler.register` 모니터 대기(락 보유 1개는 JMX `ObjectName.quote`). 톰캣 연결 2,003(15:56:02 샘플) → 6,118(15:56:12) | 15:56:05Z liveness 실패로 **재시작**(이벤트 `Killing` count 2의 마지막 시각, 보존본) |
| jchdr | Lettuce 응답 대기 142, Hikari 대기 21 | 요청 스레드 유휴 | 회복 — 단 liveness가 15:55:45·55Z에 2회 연속 실패(보존본). 톰캣 연결 1,918 |
| nrqsl | Lettuce 응답 대기 200 | 요청 스레드 유휴 | 회복 — 단 liveness가 15:55:45·55Z에 2회 연속 실패(보존본). 톰캣 연결 1,591 |

- Lettuce 대기의 호출 위치는 진입 경로(`QueueController.enter` → `QueueService.issue`·`admittedNow`·`card`·`rankOf`·`statusOf`)와 인증 필터의 블랙리스트 확인이다.
- 커넥션 풀 설정이 없어 Lettuce는 Spring Boot 기본값대로 공유 연결 하나를 쓴다(코드 확인). 이벤트 루프 스레드 상태는 파드마다 달랐고(인코딩·`runAllTasks`·응답 처리),
  두 덤프 사이 이벤트 루프 CPU 증가는 한 코어의 약 4~5%여서 이벤트 루프 포화 가설에는 불리하다(추론).
- ElastiCache 엔진 CPU는 같은 구성(2Gi) step9에서 최대 3.69%였다(CloudWatch 1분 해상도, 실측) — 진단 run 구간의 CloudWatch는 내보내지 않았다.
- `hikaricp_connections_pending`은 10초 간격 샘플에서 0이었지만, 덤프 순간에는 Hikari 대기 스레드가 있었다.
- **결론(추론)**: 요청 스레드를 묶는 원인은 하나로 확정되지 않았다 — Redis 응답 대기(공유 연결·순차 왕복), DB 커넥션 대기(풀 5),
  톰캣 연결이 가장 많았던 파드의 톰캣 등록 락 경합이 모두 관측됐다(파드별 SSE 게이지가 없어 "SSE가 몰렸다"는 톰캣 연결 수로 미루어 본 것). 재시작된 파드는 마지막 덤프에서 등록 락 경합 상태였다.
  liveness 시간 초과는 원인이 아니라 결과다.

## 6. 조치
`k8s/base/api-deployment.yaml`의 `livenessProbe.failureThreshold` 3 → **12**(period 10초 → 첫 실패 후 약 110초).

- 근거: 측정에서 첫 liveness 실패 후 약 20초(3회) 만에 재시작됐고, 포화 지속 시간은 그 재시작 때문에 알 수 없었다. 진단 run에서 회복한
  두 파드도 liveness가 2회 연속 실패했다 — 기본값 3이면 한 번 더 실패했을 때 재시작이었다(실측). 톰캣 바쁜 스레드 샘플은 24q5h 199·199(15:55:42·52),
  109(15:56:02), jchdr 200·200 뒤 1이다(10초 간격이라 지속 시간은 10~20초 이상이라는 하한만). 12회는 이 하한보다 넉넉한 여유를 두려고 고른
  **선택값**이다 — 충분한지는 재시험으로 확인한다(추론).
- readiness는 바꾸지 않는다 — 준비 안 됨은 트래픽에서 빼는 것이라 재시작을 일으키지 않는다.
- 근본 해결 후보(이 PR의 범위 밖, 코드·설정 변경과 통합 테스트 필요): ① health를 관리 포트(별도 커넥터·스레드 풀)로 분리 —
  등록 락도 피한다 ② 진입 요청의 Redis 왕복 감소(한 Lua로 묶기)나 커넥션 풀 ③ 진입 경로의 DB 조회(`requireBookable`) 캐시나 풀 크기 재산정
  ④ `mbeanregistry`(톰캣 지표)와 등록 락 경합의 trade-off 재검토.

## 7. 결과 (재측정 — before와 동일 조건)
**미측정.** 같은 workload(S-10K, Constant, SSE 50초, 메모리 2Gi)로 재시험 뒤 채운다.

## 8. 트레이드오프 / 한계 / 다음 개선
- 진짜로 멈춘(교착·무한 대기) 프로세스의 재시작이 최대 약 2분 늦어진다(그동안 readiness 실패로 트래픽에서 빠진다).
- 처리량·지연은 그대로다 — 이 조치는 재시작만 줄인다.
- 포화 중 api가 모두 readiness 실패하면(step9에서 3개 모두 실패) 재시작 대신 엔드포인트가 0개인 구간이 생긴다. readiness에 redis 체크가 있어
  같은 공유 연결을 쓴다.
- 포화가 2분을 넘는 부하에서는 여전히 재시작될 수 있다.
- 원인 진단은 덤프 2회·파드 3개의 관측이다(반복 없음, 추론 포함).

## 9. 배운 점
메모리를 올리자 OOM 뒤에 있던 liveness 재시작이 드러났고, 스레드 덤프는 한 가지 원인이 아니라 Redis 응답 대기·DB 커넥션 대기·
SSE가 몰린 파드의 톰캣 등록 락을 함께 보여 줬다(추론 포함) — probe는 판정을 가능하게 하는 완화이고, 원인은 따로 하나씩 나눠 잰다.
