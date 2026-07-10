# Improvements Log (개선 일지 — before/after 정량 기록)

측정은 **사후가 아니라 개발과 동시에**. 각 개선마다 한 문서를 만들고,
naive → 측정 → 개선 → 재측정 순서로 수치를 **커밋으로 박제**한다.
(끝나고 나면 측정 불가 — 변화는 그 순간에만 잡힌다.)

## 작성 규칙
1. 개선 착수 전 현재 상태를 측정해 `benchmarks/`에 저장하고 커밋(= before 증거).
2. 개선 후 다시 측정해 저장하고 커밋(= after 증거).
3. `docs/improvements/IMP-XXX-<slug>.md`는 [`_TEMPLATE.md`](_TEMPLATE.md) 7단계로 기록:
   **문제 정의(계층 분류) → 증상(수치) → 가설 → 도구로 검증 → 해결 → 재측정 → 한계**.
4. 가능하면 "의도적 naive 버전"을 먼저 만들어 문제를 수치로 캡처한 뒤 개선한다.
5. 해결책(캐시/락/비동기)을 먼저 넣지 말 것 — 어디서 느린지/틀리는지 **구간을 나눠 측정**한 뒤 고른다.

## 개선 목록
| ID | 제목 | 슬라이스 | 핵심 | 상태 |
|----|------|----------|------|------|
| [IMP-001](IMP-001-rtr-multitab-grace.md) | RTR 멀티탭 세션 폭파 — grace 윈도 | S01 | 멀티탭 동시요청→세션유지 (정성) | 완료 |
| [IMP-002](IMP-002-multitab-logout-sync.md) | 멀티탭 로그아웃 즉시 동기화 — BroadcastChannel | S01 | 다른 탭 즉시 반영 (정성) | 완료 |
| [IMP-005](IMP-005-kopis-coverage.md) | KOPIS 동기화 커버리지 — 청크·페이지 전량 수집 | S02 | 100건→1,907건(약 19배) | 완료 |
| [IMP-006](IMP-006-xff-dedup-bypass.md) | 조회수 dedup 우회 — XFF 신뢰 경계 | S02 | 위조 100→1건 수렴 | 완료 |
| [IMP-007](IMP-007-token-issue-dedup.md) | 1인1토큰 중복 발급 — SET NX 원자화 | S03 | 중복 19→0(단일서버 실재) | 완료 |
| [IMP-003](IMP-003-oversell.md) | 재고 초과판매 — DB 조건부 UPDATE | S04 | oversell 19→0 | 완료 |
| [IMP-004](IMP-004-queue-admission.md) | 대기열 정원 초과 — 승격 Lua 원자화 | S03 | over-admit 7→0 | 완료 |
| [IMP-008](IMP-008-payment-idempotency.md) | 결제 이중 처리 — idempotency_key UNIQUE + 조건부 전이 | S05 | 이중 PAID 7→0 | 완료 |

## 누적 지표 보드
프로젝트 전체에서 모은 정량 성과 요약: [METRICS.md](METRICS.md)
