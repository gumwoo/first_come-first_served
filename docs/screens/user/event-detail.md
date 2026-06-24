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
- [ ] 포스터 + 제목 + 주최/장르 배지
- [ ] 공연 정보(일시/장소/관람시간/관람연령/가격)
- [ ] "예매하기" CTA(primary) / 관심 등록
- [ ] 예매 오픈 안내 박스(오픈일시·1인 구매수량 제한)
- [ ] 공연정보/판매정보/유의사항 탭

## 연결
- 예매하기 → /events/:id/queue (대기열)
