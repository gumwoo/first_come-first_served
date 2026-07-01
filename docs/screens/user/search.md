# U-SEARCH · 검색 결과

- ref: `assets/screens/사용자_검색 결과.png`
- route: `/search?q=`
- slice: S02

## 목적
헤더 검색바 결과. 필터 + 인기/연관 검색어.

## 상태(states)
- `default` (결과 있음)
- `empty` (결과 없음)

## 사용 API
- `GET /search?q=&genre=&region=&status=&page=&size=` — 검색(실행 시 인기검색어 +1)
- `GET /search/popular-keywords` — 인기 검색어 TOP N (Redis ZSET)

## 화면 요소 (DoD 체크리스트)
- [~] 상단 필터 바 — 장르/지역/상태 구현. 기간/가격은 후속(가격=S04, domain/event.md)
- [x] 결과 리스트(포스터/제목/일시/장소/가격/상태배지/예매 버튼)
- [~] 우측 인기검색어(Redis ZSET 실연동 예정) · 연관검색어(목업 유지)
- [x] 정렬(공연일순) — 리스트(카드 행) 형식 고정, 뷰 토글 미사용
- [x] 페이지네이션
- [x] 결과 카드 배지(매진/오픈예정/임박 D-day) + 로딩 스켈레톤

## 연결
- 결과 항목 → /events/:id
