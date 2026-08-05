# 무중단 배포 — 어디까지 되고, 어디부터 안 되는가

- 상태: **조건 정리 완료 / 매니페스트 미작성 / 실측 전**
- 슬라이스: S09(배포) — 검증은 S10에서
- 관련: [ADR-009](../decisions/ADR-009-gitops-cd-argocd.md) GitOps CD,
  [ADR-012](../decisions/ADR-012-3az-eks-infra-cost-control.md) 인프라,
  [ADR-008](../decisions/ADR-008-kafka-event-backbone-dlq.md) SSE는 보조·DB가 진실원,
  [TS-012](../troubleshooting/TS-012-sse-reconnect-gap-no-resync.md) SSE 구독 공백

> **한 줄 요약**: 애플리케이션 쪽 준비는 됐고 Kubernetes 매니페스트가 없다.
> 매니페스트를 갖추면 **REST 요청 기준 무중단은 가능**하지만, **SSE 연결은 원리적으로 끊기고**
> **파괴적 DB 변경은 Expand-Contract를 지켜야만** 성립한다.

---

## 1. 무중단의 정의 — 하나로 뭉뚱그리지 않는다

"무중단 배포"를 한 단어로 쓰면 달성 여부를 판정할 수 없다. 계층마다 목표를 따로 둔다.

| 대상 | 목표 | 달성 가능성 |
|---|---|---|
| **REST 요청** | 배포 중 **5xx·연결 끊김 0건** | ✅ 설정으로 가능 |
| **SSE 연결** | 연결 유지 | ❌ **원리적으로 불가능** |
| **SSE 복원력** | 끊긴 뒤 재연결하고 **현재 상태로 화면 복구** | ✅ 가능(코드 반영됨) |
| **DB 스키마 변경** | 구·신 버전 동시 가동 중 무장애 | ⚠️ **개발 규율**에 달림 |
| **Kafka 소비** | 유실 없음 | ⚠️ 조건부(§7) |

**"무중단이 됩니다"보다 "어디까지 되고 어디부터 안 되며 왜 그런지"가 정확한 답이다.**

## 2. 현재 준비된 것 (애플리케이션)

| 항목 | 근거 |
|---|---|
| graceful shutdown | `application.yml` — `server.shutdown: graceful` |
| 종료 유예 | `spring.lifecycle.timeout-per-shutdown-phase: ${SHUTDOWN_TIMEOUT:30s}` |
| probe 분리 | readiness=`readinessState,db,redis` / liveness=`livenessState` |
| probe 인증 예외 | `SecurityConfig` — `/actuator/health/{liveness,readiness}` permitAll |
| **SIGTERM 전달** | `apps/api/Dockerfile` — `ENTRYPOINT ["sh","-c","exec java ..."]` |
| SSE 복원력 | `useOrder`·`useSeats`의 `onopen → refresh()` (TS-012) |

**`exec`가 핵심이다.** 없으면 `sh`가 PID 1이 되어 SIGTERM을 삼키고 graceful shutdown이
**아예 동작하지 않는다.** readiness에 Kafka를 넣지 않은 것도 의도다 — 브로커 하나 죽었다고
API 전체가 트래픽에서 빠지는 과잉 차단을 피한다.

## 3. 아직 없는 것 (Kubernetes)

**`k8s/` 디렉터리 자체가 없다.** 따라서 아래가 전부 미설정이다.

```
readinessProbe / livenessProbe 배선   (엔드포인트는 있으나 K8s가 호출하도록 연결 안 됨)
terminationGracePeriodSeconds
preStop
PodDisruptionBudget
strategy (maxSurge / maxUnavailable)
replicas / HPA
ALB Pod Readiness Gate
```

→ 현재 상태는 **"무중단 배포 구현"이 아니라 "무중단 배포가 가능한 애플리케이션 준비 완료"**다.
문서·이력서에 전자로 쓰지 않는다.

## 4. REST 롤링 배포 조건

```yaml
replicas: 2                      # 1이면 롤링 자체가 성립하지 않는다
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0            # 새 Pod가 Ready가 된 뒤에 옛 Pod를 내린다
terminationGracePeriodSeconds: 60
containers:
  - readinessProbe: { httpGet: { path: /actuator/health/readiness, port: 8080 } }
    livenessProbe:  { httpGet: { path: /actuator/health/liveness,  port: 8080 } }
    lifecycle:
      preStop: { exec: { command: ["sh", "-c", "sleep 10"] } }
```

### 60초는 어디서 나온 값인가

**grace period는 `preStop`부터 소진된다.** `preStop`이 끝난 뒤에야 SIGTERM이 가고,
거기서부터 Spring이 종료 단계를 진행한다.

```
preStop            10초
Spring 종료 최대   30초   (timeout-per-shutdown-phase)
안전 여유          20초
─────────────────────────
합계               60초
```

기본값 30초를 그대로 두면 `preStop`(10) + Spring(30) = 40초가 필요한데 30초에 **SIGKILL**이
날아가 **진행 중 요청이 끊긴다.** 다만 60초가 무조건 안전하다는 뜻은 아니다 —
실제 API 응답 시간, Kafka 리스너 종료 시간, DB 트랜잭션 길이를 측정해 조정해야 한다.

## 5. ALB 등록 해제와 Pod Readiness Gate

### 흔한 오해 정정

ALB의 **deregistration delay 기본값이 300초**인 것은 맞지만,
**"300초 동안 신규 요청이 계속 들어온다"는 뜻이 아니다.** 등록 해제가 시작되면 대상은
`draining` 상태가 되어 **신규 요청을 받지 않고** 기존 연결만 마무리한다.

### 실제 위험 구간

```
Pod 종료 시작
  → Kubernetes Service Endpoint 제거
  → AWS Load Balancer Controller가 ALB Target 해제 요청
  → 실제 Target 상태에 반영되기까지 시간차 존재     ← 여기
  → 그 전에 JVM이 종료되면 인입된 요청이 끊긴다
```

`preStop`은 **이 전파 지연을 기다리는 장치**다. 300초를 기다리는 것이 아니다.

### preStop만으로는 부족하다

`preStop`은 **내려가는 쪽**을 다루고, **올라오는 쪽**에는 다른 장치가 필요하다.
`maxUnavailable: 0`은 "새 Pod가 Ready가 되면 옛 Pod를 내린다"인데, **K8s가 보는 Ready와
ALB가 보는 Healthy가 다르다.** Pod가 Ready여도 ALB Target Group에 아직 등록·헬시가 아니면
그 사이 트래픽이 갈 곳을 잃는다.

**ALB Pod Readiness Gate**가 이 간극을 메운다 — Target Group에서 실제 `healthy`가 될 때까지
Pod를 Ready로 인정하지 않아, 옛 Pod가 너무 일찍 내려가지 않는다.

```yaml
# namespace
labels:
  elbv2.k8s.aws/pod-readiness-gate-inject: enabled
# Ingress
target-type: ip        # readiness gate의 전제
```

**필요한 조합은 하나가 아니라 세트다.**
```
replicas >= 2 · maxUnavailable=0 · maxSurge=1
readinessProbe · preStop · 충분한 terminationGracePeriodSeconds
ALB Pod Readiness Gate · deregistration delay 조정
```

## 6. SSE — 연결 무중단이 아니라 복원력

graceful shutdown은 "**진행 중 요청이 끝날 때까지** 기다린다"인데, SSE는 **끝나지 않는 연결**이다.
어떤 설정으로도 Pod 교체 시 SSE는 끊긴다. 우회가 아니라 **성질**이다.

그래서 목표를 바꾼다.
```
연결 무중단(불가능)  →  재연결 후 현재 상태 복구(가능)
```

이 프로젝트는 **SSE를 트리거로만 쓰고 진실원은 서버**에 둔다(ADR-008). 이벤트는 값을 나르지
않고 "다시 읽어라"는 신호만 준다. [TS-012](../troubleshooting/TS-012-sse-reconnect-gap-no-resync.md)에서
그 원칙을 **재연결 경로에도 적용**해 `onopen → refresh()`를 넣었다.

### 실측에서 확인할 것 (아직 안 했다)

```
1. 재연결이 실제로 일어나는가 (EventSource 자동 재연결)
2. 인증 쿠키가 재연결 요청에도 실려 가는가
3. 재연결 후 refresh()가 현재 상태를 가져오는가
4. 이벤트 공백이 화면 불일치로 남지 않는가
5. Last-Event-ID / 이벤트 리플레이가 필요한 경우가 있는가
6. 재연결까지 걸린 시간(초)
```

5번은 **필요 없을 것으로 본다** — 놓친 이벤트를 재생하지 않고 **현재 상태를 다시 읽는** 방식이라
공백의 길이와 무관하게 복구된다. 다만 확인은 실측에서 한다.

## 7. Kafka — 종료와 리밸런싱

**"유실 없음"이라고 단정하지 않는다.** 아웃박스(ADR-010)는 **발행** 유실을 막지만
**소비 측 처리**까지 자동으로 해결하지 않는다.

현재 설정 상태:
- 프로듀서: `acks: all`, `enable.idempotence: true` — 발행 쪽은 단단하다.
- 컨슈머: `ack-mode`·`enable-auto-commit` **명시 설정이 없다** → Spring 기본값에 의존 중.

정확한 표현:
```
Pod 종료·리밸런싱으로 소비가 잠시 지연될 수 있다.
at-least-once 특성상 중복 처리 가능성이 있으므로 멱등 처리가 필요하다.
유실 여부는 offset commit 시점과 DB 트랜잭션 경계를 확인해야 판정할 수 있다.
```

→ **실측 전 확인 항목**: 컨슈머 ack 모드가 무엇으로 동작하는지, offset commit이 DB 반영
**뒤에** 일어나는지. 아웃박스 소비 경로는 Redis SETNX 멱등이 있어 중복에는 견디지만,
그 경계를 문서로 확정하지 않았다.

## 8. Flyway — Expand-Contract 규칙

롤링 배포 중에는 **구버전 Pod와 신버전 Pod가 같은 DB를 동시에 본다.** Flyway는 기동 시 돌고
락으로 동시 실행은 막지만, **스키마가 바뀐 뒤에도 구버전 Pod는 계속 살아 있다.**

```
신버전 Pod 기동 → Flyway가 컬럼 삭제 → 아직 살아있는 구버전 Pod가 즉시 터진다
```

**설정으로 못 막는다. 개발 규율이다.**

```
릴리스 N    확장(Expand)   컬럼 추가는 nullable 또는 기본값. 구·신 양쪽이 동작하게.
릴리스 N+1  수축(Contract)  모든 Pod가 새 코드로 교체된 뒤 구 컬럼 참조 제거·삭제.
```

### 현재 상태 (전수 검사)

`apps/api/src/main/resources/db/migration/*.sql` **14개 파일 전수 검사** 결과:

| 패턴 | 건수 | 비고 |
|---|---|---|
| `DROP TABLE` / `DROP COLUMN` | **0** | |
| `ALTER COLUMN ... TYPE` | **0** | |
| `RENAME` | **0** | |
| `SET NOT NULL` | **0** | |
| `ALTER COLUMN ... DROP NOT NULL` | **1** | `V3__users_phone_nullable.sql` |

유일한 1건은 **제약을 푸는 방향(Expand)**이라 구버전 Pod를 깨뜨리지 않는다.
즉 **지금까지의 마이그레이션은 무중단과 호환된다.** 다만 이는 규율이 있어서가 아니라
아직 파괴적 변경이 필요하지 않았기 때문이다 — **컬럼 하나만 지워도 깨진다.**

### 앞으로의 규칙

파괴적 DDL(`DROP TABLE`·`DROP COLUMN`·`ALTER COLUMN ... TYPE`·`RENAME`·`SET NOT NULL`)은
**영구 금지가 아니라 명시적 승인 대상**으로 둔다. `SET NOT NULL`도 데이터 백필 후 별도
릴리스에서는 안전하기 때문이다.

**하네스가 강제한다**(`harness/backend/check.mjs` 규칙 14, meta fixture `be-destructive-ddl`).
승인은 마이그레이션 파일에 사유와 함께 남긴다 — PR 본문이 아니라 **코드 옆에** 근거가 남는다.

```sql
-- harness:allow-destructive-ddl: V13에서 Expand 완료, 구버전 코드에 참조 없음
ALTER TABLE orders DROP COLUMN legacy_code;
```

사유 없는 예외 주석과, 파괴적 DDL이 없는데 달려 있는 예외 주석은 **둘 다 실패**한다
(예외가 장식으로 남아 다음 사람을 오해시키는 것을 막는다).

## 9. 검증 시나리오 (S10)

**설정상 가능 ≠ 5xx 0건이 검증됨.** 지속 트래픽을 흘리면서 롤링을 걸고 측정한다.

```bash
k6 run load.js &                                  # 부하 유지
kubectl set image deploy/api api=<repo>:<새 SHA>   # 롤링 시작
```

| 측정 항목 | 판정 기준 |
|---|---|
| HTTP 5xx 건수 | **0** |
| connection reset / 요청 누락 | **0** |
| 최대 응답 시간 | 평시 대비 튐 폭 기록 |
| 롤아웃 전체 소요 시간 | 기록 |
| 종료 Pod의 **SIGKILL 발생 여부** | 없어야 함(있으면 grace period 부족) |
| SSE 재연결 시간·상태 복구 | 화면 불일치 0 |

### 예측 (실측 전에 기록한다)

맞으면 "예측하고 확인했다", 틀리면 "예측이 틀렸고 이유는 X였다" — **어느 쪽이든 기록이 남는다.**

| # | 예측 | 근거 |
|---|---|---|
| ① | grace period 기본값(30s)이면 SIGKILL이 발생한다 | preStop 10 + Spring 30 > 30 |
| ② | preStop 없이는 롤링 중 5xx가 발생한다 | Target 해제 전파 지연 |
| ③ | SSE는 롤링마다 끊긴다(불가피) | 장기 연결의 성질 |
| ④ | Web(Next.js)는 API보다 준비가 덜 돼 있다 | §10 참조 |

## 10. 현재 한계

- **매니페스트가 없다.** 위 조건은 전부 "그렇게 설정하면"이라는 가정 위에 있다.
- **실측이 없다.** `terraform apply` → 배포 → 부하 중 롤링까지 가야 판정할 수 있다.
- **Web(Next.js)은 미확인이다.** `CMD ["node","server.js"]`가 exec form이라 SIGTERM은 받지만,
  Next standalone 서버가 graceful shutdown을 하는지 **확인하지 않았다(추정 아님, 미확인)**.
  브라우저의 `/api/*`는 Ingress가 API Service로 직접 라우팅해 Web을 거치지 않으므로 영향이
  작을 것으로 보지만, **SSR/HTML 응답은 별도로 검증해야 한다.**
- **Kafka 컨슈머 경계가 문서로 확정되지 않았다**(§7).
- **하네스가 막는 것과 못 막는 것을 구분한다.** 파괴적 DDL과 SSE 복구 경로 **부재**는 정적으로
  막힌다(규칙 14 / FE 규칙 7). 그러나 **복구가 실제로 동작하는지**는 정적 검사 밖이다 —
  롤링 실측이 유일한 검증 수단이다. 응답 역전·경쟁 조건도 정적으로 잡을 수 없다.
- **Blue-Green·Canary는 범위 밖이다.** 먼저 롤링을 실측하고, 부족하다고 판단되면 그때
  Argo Rollouts 등을 검토한다 — 컴포넌트를 늘리기 전에 **기본형의 한계를 실측으로 아는 것**이
  순서다.
