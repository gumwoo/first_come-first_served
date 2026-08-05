# 배포·운영 준비 (S09) — 한 장 지도

- 상태: 계획(미착수). 구현 전 이 문서로 전체를 조망한다.
- 성격: **인프라 트랙**(기능 슬라이스 아님). 지도의 작업 큐로 S09에 편입 — [docs/screens/_index.md](../screens/_index.md).
- 우선순위: **S08 아웃박스(정합성)를 먼저** 끝낸 뒤 이 배포로. 배포는 "코드 선반영 완료"라 실증만 하고 동결.
- 후속: S10 부하테스트·모니터링은 이 배포 위에서 실측.

## 문서 5종 (상세)
| 문서 | 내용 |
|------|------|
| [aws-eks-deploy-plan.md](aws-eks-deploy-plan.md) | EKS + Strimzi 멀티브로커 배포 단계(Phase 1~8)·역할분담·DoD |
| [terraform-design.md](terraform-design.md) | **Phase 3 구현 설계** — state 분리·CIDR·노드그룹·리소스 예산표·apply/destroy 순서, **환경 분리 기각 근거(§10)** |
| [zero-downtime-deployment.md](zero-downtime-deployment.md) | **무중단의 정의와 조건** — REST/SSE/DB를 나눠서. ALB readiness gate, Expand-Contract, 검증 시나리오·예측 |
| [app-changes-for-k8s-kafka.md](app-changes-for-k8s-kafka.md) | 파티션↑·복제↑·probe·graceful shutdown·**멀티팟 대응(D 섹션)** 코드/설정 변경 |
| [portfolio-framing-and-decisions.md](portfolio-framing-and-decisions.md) | 납득 원칙·최종 스코프·**MSA 고려·기각**·면접 서사 |

## 멀티팟 준비 체크리스트

> **①②③④ 코드 선반영 완료** — 단일 Pod에서도 동작·통합테스트 통과. 다만 ①②의 **cross-Pod 실증**(연결 Pod ≠ 소비 Pod 시 팬아웃, 다중 Pod 스케줄 중복 억제)은 다중 Pod 배포(S09) 시 부하로 확인. "코드 완료 / 다중 Pod 실측 예정".

| # | 항목 | 왜 필요 | 상태 | 상세 |
|---|------|---------|------|------|
| ① | **SSE Registry 팬아웃** | 인메모리 SSE는 Pod 간 공유 안 됨 → 소비 Pod ≠ 연결 Pod면 알림 누락 | **코드 완료**(Redis pub/sub, 실측 예정) | app-changes D-1 |
| ② | **스케줄러 중복 실행(ShedLock)** | @Scheduled가 Pod마다 실행 → 중복(정합성은 조건부 UPDATE로 안전, 낭비만) | **코드 완료**(스윕 4개, 실측 예정) | app-changes D-2 |
| ④ | **정확한 seat.hold.expired 알림** | sweep이 결제로 안 풀린 SOLD 좌석까지 알림(과알림 오탐) | **완료**(홀드별 조건부 전이) | TS-011 §7 |
| ③ | **PG 승인 후 롤백 보상** | 실 PG 승인 뒤 DB 롤백 시 승인만 남음(미아 승인) | **완료**(finalizePaid void, edge는 outbox 후속) | TS-011 §7·§한계 |

### ① SSE 팬아웃 — 왜 Redis pub/sub인가 (Kafka 아님)
```
결제 → Kafka(order-events)          ← 내구성 백본·재시도·DLQ·리플레이
        → consumer(한 Pod가 소비)
             → Redis pub/sub publish → 모든 Pod subscribe
                  → 각 Pod가 로컬 SSE Registry 확인 → 연결 가진 Pod가 브라우저 전송
```
- **Kafka는 계속 쓴다**(내구성 이벤트 백본). Redis pub/sub은 그걸 대체하는 게 아니라 **Pod 간 팬아웃 단계 추가**.
- **왜 Kafka로 팬아웃 안 하나**: consumer group은 이벤트를 그룹 내 **한 Pod에만** 전달 → 모든 Pod가 받게 하려면 Pod마다 다른 group = 임시 group 무한 생성·중복 소비. 과한 구조.
- **왜 Redis pub/sub인가**: 이미 Redis 사용(대기열·토큰·랭킹) → 새 컴포넌트 0. "구독한 모든 Pod에 fire-and-forget 팬아웃"에 용도 정확. **best-effort(미저장)** 성격이 "SSE는 보조·DB가 진실원(ADR-008)"과 일치. "반드시 한 번 이상 전달" 요구 생기면 그때만 Kafka 알림전용/Redis Streams 고려.
- 비유: Kafka=공식 문서 전달 시스템 / Redis pub/sub=사무실 방송 / SSE=직원에게 직접 전달.

### ③ 이 항목만 K8s와 무관 (정직)
①②는 "여러 Pod로 띄우니까" 생기지만, **③은 orchestrator가 아니라 "실 결제 게이트웨이"에 달렸다.** EKS에 올려도 `payment.gateway=mock`(또는 Toss 테스트키)이면 실제 돈이 없어 DB 롤백만으로도 사실상 무해하다. 다만 **실 PG를 전제로 보상 로직을 미리 배선**해 두면(승인 실패 경로에서 `gateway.refund`로 void), 나중에 `payment.gateway=toss`로 바꾸는 순간 코드 변경 없이 안전해진다 → 그래서 **선반영**(TS-011 §7). 남은 edge(승인 직후 크래시)는 **S08 아웃박스**로 닫는다(정합성 발행 축 완성).

## 새 ADR/IMP/TS는?
이 항목들은 "예정 작업"이라 지금은 지도 + 이 문서로 추적한다. 구현하다 결함이 나면 **TS**, 스케일 수치가 나오면 **IMP**(S10), 되돌아볼 설계 결정이면 **ADR**로 그때 승격한다.

- **[ADR-009](../decisions/ADR-009-gitops-cd-argocd.md) GitOps CD — ArgoCD (Proposed)**: 배포를 push(Actions apply)가 아니라 **pull 기반 GitOps(ArgoCD)**로. Terraform=인프라 / Actions=빌드·이미지·Git 갱신 / **ArgoCD=앱 동기화·드리프트·롤백**. "Git이 단일 진실원" 철학을 런타임까지 확장. Application은 최소 유지(과설계 방지). Phase 6에서 구현 시 Accepted로 승격.
- **[ADR-012](../decisions/ADR-012-3az-eks-infra-cost-control.md) 3AZ EKS 인프라 — 이중화 범위와 비용 통제 (Proposed)**: 3 AZ·AZ별 NAT·AZ별 노드그룹·CA를 **왜** 그렇게 골랐는지. 판단 기준은 "핵심 주장은 실증하고, 기본 안전장치는 근거를 남긴다". 상시 운영 대신 **apply/destroy**로 비용 통제. 구현 구조는 [terraform-design.md](terraform-design.md).
