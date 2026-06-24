# Common · Design System

모든 화면이 참조하는 디자인 토큰. 화면 MD에 색/간격을 복붙하지 말고 여기를 참조한다.
(실제 값은 화면 이미지 기준으로 구현 시 확정 — 아래는 기준 토큰 명세)

## 브랜드
- 서비스명: **FlowTicket** (헤더 좌상단 로고)
- 톤: 밝은 배경 + 블루 액센트, 데이터/테이블 위주의 클린한 어드민 스타일

## 컬러 토큰 (Tailwind/shadcn CSS 변수)
- `--primary`: 메인 블루 (CTA 버튼, 링크, 액티브 상태)
- `--background` / `--foreground`: 화이트 / 다크그레이 텍스트
- `--muted` / `--muted-foreground`: 보조 영역/회색 텍스트
- `--border`: 카드·테이블 경계선
- 상태색:
  - success(green): 완료/PASS/정상
  - warning(amber): 대기/임계 근접
  - destructive(red): 실패/매진/에러/취소
  - info(blue): 안내 박스

## 타이포
- 본문/숫자 강조: 큰 숫자 지표(대기순번, 카운트다운)는 굵게 대형으로
- 페이지 제목 → 섹션 제목 → 본문 3단계

## 공통 컴포넌트 (shadcn 기반)
- `Button` (primary/outline/destructive)
- `Card` (지표 카드, 요약 카드)
- `Badge` (상태 배지: 예매가능/매진/대기/PASS/RUNNING 등)
- `Table` (주문/이벤트/로그 목록 — 페이지네이션 포함)
- `Input` / `Select` / `DatePicker` (필터 바)
- `Dialog` (확인 모달: 취소/환불)
- `Stepper` (예매 단계 표시: 좌석 → 결제 → 완료)
- `Countdown` (결제 09:42, 입금기한 등 타이머)
- `StatCard` (상단 지표 4~6개 카드 묶음)

## 레이아웃 규칙
- 사용자: 상단 글로벌 헤더 + 중앙 컨텐츠 (max-width 컨테이너)
- 운영자/개발자: 좌측 사이드바(또는 상단 탭) + 필터바 + 지표카드 + 메인 테이블/차트
- 자세한 건 [layout.md](layout.md)
