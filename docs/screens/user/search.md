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
- `GET /search?q=&genre=&status=&page=&size=` — 검색(실행 시 인기검색어 +1)
- `GET /search/popular-keywords` — 인기 검색어 TOP N (Redis ZSET)

## 화면 요소 (DoD 체크리스트)
- [~] 상단 필터 바 — 장르/상태 구현. 지역/기간/가격은 스키마 부재로 후속(domain/event.md)
- [x] 결과 리스트(포스터/제목/일시/장소/가격/상태배지/예매 버튼)
- [~] 우측 인기검색어(Redis ZSET 실연동 예정) · 연관검색어(목업 유지)
- [x] 정렬(공연일순)/리스트·그리드 토글
- [x] 페이지네이션

## 연결
- 결과 항목 → /events/:id
