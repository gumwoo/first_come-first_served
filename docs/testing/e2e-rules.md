# E2E 테스트 규칙 (단일 출처)

Playwright로 **Critical User Flow만** 검증한다. 목적은 FE↔BE 경계·SSR·SSE·UI 상태 결함을
잡는 것(단위/통합이 못 잡는 계층). 위치: 최상위 `e2e/`(웹 앱 의존성과 분리된 독립 인프라).

## 대상 (Critical User Flow)
- 인증: 로그인 / 회원가입
- 예매 세로선: 공연 상세 → 예매하기 → 대기열 입장 → 좌석 선택 → 선점 완료
- 예외 흐름(= 과거 수동으로 잡던 버그, TS 참조): 매진 / 대기만료 / 선점 실패(MAX 안내) /
  만료 후 재예매 / 선점 취소 복귀
- (S05) 결제 카드/간편/vbank → 완료(QR) / 실패

## 제외
- 부가 UI(카테고리탭, 관심 등), 관리자(S07), 단순 정적 페이지.
- 220개 전 페이지 X — "망가지면 매출/신뢰가 깨지는" 것만.

## 3대 안정성 원칙 (flaky 방지)
1. **하드 대기 금지.** `waitForTimeout` X → 요소가 나타날 때까지 조건 대기(`expect(...).toBeVisible()`).
   대기열 승격(1.5s 스케줄러)·SSE 반영은 조건 대기로만.
2. **시맨틱 셀렉터.** CSS 클래스/DOM 구조 X → `getByRole`/`getByText`(역할·이름) 사용.
3. **번인.** 새 테스트는 머지 전 `--repeat-each=20`으로 반복 실행, 한 번이라도 실패하면 flaky로 차단.

## 상태 빌드업 (속도·독립성)
- 매 테스트에서 로그인→대기열을 반복하지 않는다. 테스트 API/시드로 백엔드를 "특정 상태"로
  먼저 세팅하고, 브라우저는 검증할 화면부터 시작(예: 입장 토큰 발급 후 `/events/{id}/seats?qt=`).

## 격리·병렬성 (중요)
- KOPIS 이벤트/좌석은 **공유 자원**이라, 병렬 워커가 같은 좌석을 다투면 경합(SOLD_OUT)해 오탐이 난다.
- 따라서 **직렬 실행(`workers: 1`)** 으로 결정론 확보(크리티컬 플로우 소수라 속도 손해 미미).
- 추가 완화: `seedAdmittedUser`가 이벤트를 랜덤 분산 선택(여유 좌석 있는 것). 좌석 격리가 필요한
  케이스는 좌석 id/좌석수를 API로 계산해 결정론화.

## 모킹 정책 (통제 밖 의존성)
- **실 PG 미연동** — 결제는 시뮬레이터(목). E2E는 시뮬레이터의 성공/실패 규칙으로 두 화면 모두 검증.
- KOPIS는 런타임 호출 없음(시딩됨). 외부 콜(OAuth 등)은 `page.route`(브라우저) 또는
  E2E 전용 env(SSR/프록시)로 고정 응답.

## 환경
- **최초 1회 셋업**: `cd e2e && npm install && npm run install:browsers`(Chromium 다운로드).
  이후 `npm run e2e`. CI는 `npx playwright install --with-deps chromium` 스텝으로 동일 처리.
- `E2E_BASE_URL`(기본 `http://localhost:3000`). 로컬은 dev 서버 재사용, CI는 풀스택 기동 후 주입.
- Redis는 5.0+(ZPOPMIN) 필수 — 로컬 버전 불일치 주의(TS-002).

## CI (구현 완료 — ci.yml `e2e` 잡)
- PR마다 풀스택(postgres16+redis7.4 → 백엔드 bootJar → 좌석 시드 → 프론트 → Playwright) 기동해
  critical-flow E2E 실행 → 실패 시 머지 차단 + trace/로그 아티팩트 업로드.
- CI 빈 DB는 KOPIS 없으니 `e2e/ci/seed.sql`로 판매중 이벤트·좌석을 멱등 시드.
- 실패 trace는 에이전트가 파싱해 원인 분석·수정(자가 개선 루프).
