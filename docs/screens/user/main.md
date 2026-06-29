# U-MAIN · 메인

- ref: `assets/screens/사용자_메인 홈.png`
- route: `/`
- slice: S02

## 목적
서비스 진입점. 대표 공연 배너 + 인기/실시간 랭킹 + 카테고리.

## 상태(states)
- `default`
- `loading` / `empty`

## 사용 API
- `GET /events/popular` — 인기 TOP / 실시간 랭킹
- `GET /events?...` — 카테고리/장르별 목록

## 화면 요소 (DoD 체크리스트)
- [x] 대형 히어로 배너(대표공연, "예매하기" CTA)
- [x] 카테고리/장르 필터 탭
- [x] 인기 공연 TOP 카드 그리드
- [x] 오픈 예정 공연
- [x] 실시간 랭킹 사이드 리스트 (조회수 집계 전 — 임시 데이터)
- [x] 그리드/리스트 뷰 토글

## 연결
- 공연 카드 → /events/:id
- 검색 → /search
