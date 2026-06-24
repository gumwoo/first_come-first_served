# Frontend Rules (Next.js App Router)

엄격 모드. ★ = 하네스 정적 강제.

## 1. 폴더 구조
```
apps/web/src
 ├─ app/                 # 라우트(App Router) — page.tsx는 얇게
 ├─ features/<slice>/    # auth, event, queue, seat, order, payment, admin, dev
 │   ├─ api/             # API client (path fragment 사용)
 │   ├─ components/      # 해당 기능 UI
 │   ├─ hooks/           # useXxxQuery / useXxxMutation
 │   ├─ store/           # zustand (필요 시)
 │   └─ types.ts         # 상태/응답/이벤트 타입 ★ (계약 검사 대상)
 ├─ components/ui/       # shadcn 공용 컴포넌트
 ├─ lib/                 # apiClient, queryClient, utils
 └─ types/contracts.ts   # BE 계약 미러 (enum/event) ★
```

## 2. 계층/참조 규칙 ★
- ★ `components/ui/**`(프레젠테이션)는 도메인 로직/직접 fetch 금지
- 데이터 fetch는 `features/*/hooks`의 TanStack Query 훅에서만
- ★ API 호출 path는 `features/*/api`에서만. 컴포넌트에서 fetch 직접 호출 금지

## 3. 상태 관리
- 서버 상태: TanStack Query (캐시/로딩/에러)
- 클라 전역 상태(대기열 토큰 등): Zustand
- 폼: React Hook Form + Zod (입력 검증)
- ★ 화면 MD의 모든 state가 컴포넌트에 구현되어야 함(done-criteria)

## 4. 타입/계약 동기화 ★
- BE enum/이벤트는 `types/contracts.ts`에 미러링
- ★ `contracts/enums.yaml`·`events.yaml`과 FE 타입이 일치해야 함(하네스 diff)
- API 응답 타입은 공통 래퍼 `{ data }` / `{ error }` 기준

## 5. 네이밍 ★
- 컴포넌트 `PascalCase`, 훅 `useXxx`, 타입 `XxxResponse`/`XxxState`
- API 함수: `getXxx/postXxx/...` (path fragment 상수로 분리)
- 이벤트 구독 목록은 `features/*/api/events.ts`에 배열 상수로 ★

## 6. 스타일
- Tailwind + shadcn만. ★ 인라인 hex 색상/임의 px 하드코딩 금지 → 토큰 사용
- 자세한 토큰은 design-rules.md / design-system.md

## 7. 금지 ★
- `contracts/allowed-stack.yaml` 밖 의존성 추가 금지
- `any` 남용 / `@ts-ignore` (불가피 시 사유 주석 필수)
- 컴포넌트에서 직접 외부 API(KOPIS 등) 호출 금지 — 항상 우리 BE 경유
