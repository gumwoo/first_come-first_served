# IMP-024 · api liveness 실패 허용 3회 → 12회(Redis 응답 대기로 요청 스레드가 포화될 때 kubelet 재시작 방지)

- 슬라이스: 인프라(100K 부하 검증 측정 세션)
- 날짜: 2026-10-06
- 유형: 정량(수치)
- 관련 커밋/PR: `3505617`(before, #330 머지) → 이 PR(after)
- 원시 데이터: `artifacts/loadtest/20261005-1440/`(레포에 커밋하지 않는다 — [loadtest-100k-plan §7](../testing/loadtest-100k-plan.md))
- 상태: **before 측정 완료 · after(같은 조건 재시험) 미측정** — 재시험 뒤 §7을 채운다

## 1. 상황 (Context)
[IMP-023](IMP-023-api-memory-sse-connections.md)에서 api 메모리를 2Gi로 올린 뒤 S-10K(계획서 §6 단계 6과 같은 workload)를 다시 걸었다.
OOM은 사라졌지만 api 3개가 모두 run 중 재시작해 단계 6을 여전히 판정할 수 없었다.

## 2. 문제 정의 + 분류
진입 부하로 api의 톰캣 요청 스레드(200)가 모두 막히면, 같은 스레드 풀에서 처리되는 liveness 요청도 시간 초과돼 kubelet이
**바쁘지만 살아 있는** 파드를 재시작한다. 재시작은 감사 로그(승격 기록)를 지워 단계 6의 사후 정합성 판정을 막는다.

- 계층: 스레드·커넥션 풀(요청 스레드) + 플랫폼 설정(probe)
- 이 개선의 목표: **재시작을 없애 단계 6을 판정 가능한 조건으로 만드는 것**. 진입 처리량·지연 개선은 목표가 아니다.

## 3. 증상 (측정된 증거)
run `step9-10k_constant-r1`(S-10K, 메모리 2Gi, 실측):

| 지표 | 값 |
|---|---|
| api 재시작 | 3개 모두(종료 코드 143·137) |
| 재시작 원인 | Kubernetes 이벤트 15:47:34~35Z `Liveness probe failed: … context deadline exceeded` → `failed liveness probe, will be restarted` |
| run 시작 → 재시작 | 약 30초(run 시작 15:47:04Z, 발생기 첫 진입 약 15:47:06Z) |
| 진입 지연 p50 / p95 | 10.71s / 23.04s |
| 단계 6 사후 정합성 | 판정 불가(승격 감사 줄 0건, run 중 재시작 3) |

## 4. 가설
- 가설 1: 요청 스레드가 무언가를 기다리며 모두 막혀, 같은 풀의 liveness 요청이 1초(기본 `timeoutSeconds`) 안에 응답하지 못한다.
- 가설 2: 기다리는 대상은 DB·CPU가 아니다(같은 세션 단계 6: Hikari pending 0, api CPU 0.10~0.14 core, RDS·ElastiCache 한가 — 실측).

## 5. 검증
같은 workload의 진단 run(`diag-10k_constant-r1`, 판정용 아님)에서 진입 포화 구간에 api 파드마다 SIGQUIT 스레드 덤프를 2회 떴다(실측):

- 첫 덤프에서 톰캣 요청 스레드 200개 중 **142~200개가 Lettuce `AsyncCommand.await`**(Redis 응답 대기)에 있었다.
  호출 위치는 진입 경로의 순차 Redis 호출이다 — `QueueService.issue`, `admittedNow`, `card`, `rankOf`, `statusOf`,
  그리고 인증 필터의 `TokenBlacklistService.isBlacklisted`.
- Lettuce 이벤트 루프 스레드(`lettuce-nioEventLoop-4-1`)는 명령 인코딩 중(RUNNABLE)이었다. 커넥션 풀 설정이 없어
  Spring Boot 기본값대로 공유 연결 하나를 쓴다(코드 확인).
- ElastiCache 엔진 CPU는 같은 workload에서 최대 약 4%였다(단계 6 CloudWatch, 실측) — 기다림은 서버가 아니라 클라이언트 쪽
  (공유 연결 하나·요청당 순차 왕복)에서 생긴 것으로 본다(**추론** — 연결·이벤트 루프별 지연을 직접 재지는 않았다).
- 따라서 가설 1을 이 조건에서 확인했다. liveness 시간 초과는 원인이 아니라 결과다.

## 6. 조치
`k8s/base/api-deployment.yaml`의 `livenessProbe.failureThreshold` 3 → **12**(period 10초 → 약 2분).

- 근거: 측정에서 요청 스레드 포화가 30~40초 이어졌고, 기존 허용(약 30초)을 넘겨 재시작됐다. 2분은 그 포화 구간을 넉넉히 덮는다(실측 기반 선택값, 최적값 아님).
- readiness는 바꾸지 않는다 — 준비 안 됨은 트래픽에서 빼는 것이라 재시작을 일으키지 않는다.
- 근본 해결은 이 PR의 범위가 아니다: ① health를 관리 포트(별도 커넥터·스레드 풀)로 분리 ② 진입 요청의 Redis 왕복 감소(한 Lua로 묶기)나 커넥션 풀 도입.
  둘 다 코드·보안 설정 변경과 통합 테스트가 필요해 따로 한다.

## 7. 결과 (재측정 — before와 동일 조건)
**미측정.** 같은 workload(S-10K, Constant, SSE 50초, 메모리 2Gi)로 재시험 뒤 채운다.

## 8. 트레이드오프 / 한계 / 다음 개선
- 진짜로 멈춘(교착·무한 대기) 프로세스의 재시작이 최대 약 2분 늦어진다.
- 처리량·지연은 그대로다 — 이 조치는 재시작만 막는다. 진입 지연(SLO)은 Redis 왕복 감소로 따로 다룬다.
- 스레드가 2분 넘게 포화되는 더 큰 부하에서는 여전히 재시작될 수 있다.
- Redis 클라이언트 쪽 병목은 덤프와 코드 판독으로 좁힌 것이지 지연 분해 측정이 아니다(추론).

## 9. 배운 점
메모리를 올리자 OOM 뒤에 숨어 있던 두 번째 실패(liveness 재시작)가 드러났고, 스레드 덤프가 그 아래의 원인 —
Redis 서버가 아니라 클라이언트 쪽 공유 연결과 요청당 순차 왕복 — 을 가리켰다. probe 설정은 완화이고, 원인은 따로 고친다.
