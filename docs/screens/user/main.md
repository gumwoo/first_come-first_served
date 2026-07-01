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
- `GET /events/popular` — 인기 공연 TOP 10 (누적 조회수)
- `GET /events/ranking/realtime` — 실시간 랭킹 (지수감쇠 조회수)
- `GET /events?status=SCHEDULED&size=8` — 오픈 예정 전용(클라 필터 아님)
- `GET /events?size=24` — 전체 공연 티저(전체는 /search에서 페이지네이션)

## 화면 요소 (DoD 체크리스트)
- [x] 대형 히어로 **슬라이더**(인기 TOP 자동 회전, dot 이동, "예매하기" CTA)
- [x] 카테고리/장르 필터 탭 (메인 그 자리 필터, 페이지 이동 X)
- [x] 검색창(검색 버튼/Enter → `/search`로 이동)
- [x] 인기 공연 TOP 카드 그리드 (누적 조회수 — 구현 예정)
- [x] 오픈 예정 공연 (status=SCHEDULED 전용 조회 — 24개 샘플 필터 아님)
- [x] 전체 공연 24개 티저 + "전체 보기 →" (→ /search 페이지네이션)
- [~] 실시간 랭킹 사이드 리스트 — 지수감쇠 조회수로 교체 예정(현재 임시)
- 카드(그리드) 형식 고정 (뷰 토글 미사용)
- [x] 공연 카드 배지(매진/오픈예정/임박 D-day) + 로딩 스켈레톤

## 연결
- 공연 카드 → /events/:id
- 카테고리 탭 → 메인 그 자리 필터(이동 X). 선택 시 인기/오픈예정(전 장르)은 숨기고 해당 카테고리 목록만.
- 검색창 검색 버튼 → /search?q=(&genre= 선택된 카테고리 동반)
- "전체 보기 →" → /search (필터 없으면 전체 공연 페이지네이션 조회)
