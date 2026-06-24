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
- `GET /search?q=&genre=&region=&period=...`

## 화면 요소 (DoD 체크리스트)
- [ ] 상단 필터 바(장르/지역/기간/가격/상태)
- [ ] 결과 리스트(포스터/제목/일시/장소/가격/상태배지/예매 버튼)
- [ ] 우측 인기검색어 · 연관검색어
- [ ] 정렬/리스트·그리드 토글
- [ ] 페이지네이션

## 연결
- 결과 항목 → /events/:id
