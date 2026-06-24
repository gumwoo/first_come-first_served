# Design Rules

★ = 하네스 일부 강제(하드코딩 검출).

## 1. 토큰 사용 ★
- 색/간격/타이포는 design-system.md 토큰(Tailwind theme/CSS 변수)만.
- ★ 인라인 hex(`#xxxxxx`)·임의 px 금지. 토큰/유틸 클래스 사용.

## 2. 컴포넌트 재사용
- 공통 요소(Button/Card/Badge/Table/Dialog/Stepper/Countdown/StatCard)는
  `components/ui`의 shadcn 기반 컴포넌트 재사용. 중복 구현 금지.
- 헤더/푸터/레이아웃은 layout.md 공통 사용(재구현 금지).

## 3. 상태 표현 일관성
- 상태배지 색: 정상/PASS=green, 대기=amber, 매진/실패/취소=red, 안내=blue.
- 로딩/빈상태/에러 상태를 각 화면에 반드시 표현(화면 MD states 기준).

## 4. 접근성/반응형
- 버튼/링크 명확한 라벨, 색만으로 정보 전달 금지(아이콘/텍스트 병행).
- 데스크탑 우선, 최소 반응형(테이블 가로 스크롤 허용).

## 5. 이미지 기준
- 각 화면은 screens/*.md의 `ref` 이미지를 시각 기준으로 구현.
