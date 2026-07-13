# Troubleshooting Log (트러블슈팅 — 증상→근본원인→해결→재발방지)

실사용/수동 검증 중 발견한 결함과 인프라 문제를 **증상에서 근본 원인까지 추적한 기록**.
IMP(정량 before/after 개선)와 달리, 여기 문서는 "무엇이 왜 깨졌고, 어떻게 원인을 좁혔는지"를 남긴다.
(측정 수치가 핵심이 아니라 **디버깅 과정과 재발 방지책**이 핵심)

## 작성 규칙
1. `docs/troubleshooting/TS-XXX-<slug>.md` 한 문서에 한 사건.
2. 순서: **증상 → 재현 → 조사(관측 로그/명령) → 근본 원인 → 해결 → 재발 방지**.
3. 관측한 로그·명령어·수치를 그대로 박제(끝나면 재현 불가한 경우가 많다).
4. 코드 수정이 있으면 관련 커밋/PR, 없으면(인프라 등) 조치 내용을 남긴다.

## 목록
| ID | 제목 | 슬라이스 | 유형 | 상태 |
|----|------|----------|------|------|
| [TS-001](TS-001-queue-reentry-after-expiry.md) | 입장 만료 후 재예매 불가 — 죽은 토큰이 1인1토큰을 점유 | S03 | 정합성 버그(코드) | 해결 |
| [TS-002](TS-002-local-redis-version-zpopmin.md) | 대기열 승격 매 틱 실패 — 로컬 구버전 Redis(ZPOPMIN 미지원) | S03 | 인프라(환경) | 해결 |
| [TS-003](TS-003-queue-status-auth-poll-rtr-storm.md) | 대기 상태 폴링이 토큰 회전 폭주 유발 — 인증 경계 오배치 | S03/S01 | 인증 경계(코드) | 해결 |
| [TS-004](TS-004-oauth-empty-client-id-context-fail.md) | 키 없는 환경 부팅 실패 — OAuth2 client-id 빈 문자열 | S01 | 설정/환경 | 해결 |
| [TS-005](TS-005-s04-exception-flow-verification.md) | S04 예외 흐름 실검증 — 매진 트리거 + 대기만료 범위 | S04 | 검증 로그 | 완료 |
| [TS-006](TS-006-seat-hold-error-not-surfaced.md) | 좌석 선점 실패가 화면에 안 뜸 — MAX_PER_USER 등 무음 처리 | S04 | UX 결함(FE) | 해결 |
| [TS-007](TS-007-release-hold-status-lost-context-clear.md) | 선점 해제 시 좌석만 풀리고 홀드는 HELD로 남음 — 벌크 UPDATE 컨텍스트 클리어 | S04 | 정합성 버그(코드) | 해결 |
| [TS-008](TS-008-hold-cross-event-seat-ownership.md) | 좌석 선점 시 seatIds의 이벤트 소속 미검증 — 교차 이벤트 선점(IDOR) | S04 | 정합성/보안(코드) | 해결 |
| [TS-009](TS-009-why-e2e-adoption.md) | 단위/통합은 초록인데 버그는 수동으로만 잡힘 — E2E(Playwright) 도입 | 횡단 | 검증 전략/회고 | 진행 |
| [TS-010](TS-010-vbank-secret-lost-context-clear.md) | 가상계좌 발급 정보(secret/계좌/기한)가 DB에 안 남음 — 벌크 UPDATE 컨텍스트 클리어(TS-007 재발) | S05 | 정합성 버그(코드) | 해결 |
