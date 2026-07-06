# U-EVENT-DETAIL · 공연 상세

- ref: `assets/screens/사용자_공연 상세.png`
- route: `/events/:id`
- slice: S02

## 목적
공연 상세 정보 + 예매 진입. KOPIS 메타 + 우리 거래정보(가격/회차) 결합.

## 상태(states)
- `default` (예매가능)
- `before-open` (예매 오픈 전 — 오픈일시 안내)
- `sold-out` (매진 → /events/:id/sold-out 유도)

## 사용 API
- `GET /events/:id`

## 화면 요소 (DoD 체크리스트)
- [x] 포스터 + 제목 + 주최/장르 배지
- [x] 공연 정보(일시/장소/관람시간/관람연령)
- [~] **예매 기준 가격**(우리 `event_seat_prices` 최저가~범위) + **원 공연 안내**(KOPIS priceText 원문, 무료/미정은 숨김) — 가격 자리 분리(S04에서 실데이터)
- [x] "예매하기" CTA(primary) / 관심 등록 (관심은 UI만 — wishlist 도메인 후속)
- [x] 예매 오픈 안내 박스(before-open: 오픈일시·1인 구매수량 제한)
- [x] 공연정보/판매정보/유의사항 탭

## 연결
- 예매하기 → /events/:id/queue (대기열)
