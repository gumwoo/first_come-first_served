# U-SOLD-OUT · 매진

- ref: `assets/screens/사용자_매진.png`
- route: `/events/:id/sold-out`
- slice: S04

## 목적
잔여석 0일 때 진입. 취소표/재오픈 안내 + 대체 공연 추천.

## 상태(states)
- `sold_out`

## 트리거
- 좌석선택/대기열에서 재고 0(`SOLD_OUT`) 응답 시 리다이렉트

## 사용 API
- `GET /events/:id` (매진 상태 확인)
- `GET /events/popular` (추천)

## 화면 요소 (DoD 체크리스트)
- [ ] "매진되었습니다" 헤드라인 + 잔여석 0 배지
- [ ] 공연 요약 카드(포스터/일시/장소/가격)
- [ ] 취소표·재오픈 안내 박스
- [ ] 추천 공연 카드 2개
- [ ] CTA: 다른 공연 보기 / 이벤트 상세 / 예매내역

## 연결
- 다른 공연 → /  · 추천카드 → /events/:id
