# CLAUDE.md — 작업 규칙 (매 세션 필독)

FlowTicket(선착순 예매 시스템) 포트폴리오 프로젝트. 아래는 **반드시 지키는 규칙**이다.

## PR 규칙 (필수)
모든 PR 본문은 [`.github/pull_request_template.md`](.github/pull_request_template.md)의 **7개 섹션을
전부** 채운다. 해당 없으면 "해당 없음"으로 명시하고 섹션을 삭제하지 않는다.

1. 배경 / 목적 (Why) 2. 변경 사항 (What) 3. 구현 노트 (How)
4. 테스트 / 검증(체크박스) 5. 스크린샷 / 데모 6. 영향 범위 & 리스크 7. 관련

- 파일 나열이 아니라 **동작/의도 중심**으로 쓴다.
- 브랜치에 커밋을 더 얹으면 **PR 본문도 함께 갱신**해 실제 내용과 항상 일치시킨다.
- `gh pr create --body-file`로 템플릿을 채워 생성한다(빈 본문 금지).

## 커밋 규칙
- 커밋 메시지·PR 본문에 `Co-Authored-By: Claude ...` 트레일러나 "Generated with Claude Code"
  문구를 **넣지 않는다**(포트폴리오 = 본인 단독 작업). 기존 히스토리는 재작성하지 않음.
- 기본 브랜치 직접 커밋 금지 — 브랜치 파서 PR로 올린다.

## 검증
- 프론트: `corepack pnpm@9.12.0 --dir apps/web typecheck | lint | build`
- 계약/정적 가드: `npm run harness:check` (meta 23 + backend + frontend)
- 백엔드 컴파일/통합테스트: **로컬 gradle 없음 → CI(Testcontainers)에서만 검증**.

## 문서 규율
- 정량 개선 → `docs/improvements/IMP-XXX`, 설계 결정 → `docs/decisions/ADR-XXX`,
  디버깅/사건 회고 → `docs/troubleshooting/TS-XXX`, 슬라이스 큐 → `docs/screens/_index.md`.
- 비밀(KOPIS/OAuth 키 등)은 **환경변수로만**. 코드·설정·채팅에 붙여넣지 않는다.
