# O-EVENT-DETAIL · 이벤트 상세 운영

- ref: `assets/screens/운영자_이벤트 상세 운영.png`
- route: `/admin/events/:id`
- slice: S07

## 목적
단일 이벤트의 실시간 운영. 재고/대기열/Consumer Lag/판매 상태 제어.

## 사용 API
- `GET /admin/events/:id` / `PATCH /admin/events/:id`

## 화면 요소 (DoD 체크리스트)
- [ ] 이벤트 헤더(포스터/제목/장소/일시) + 운영 액션(예매 오픈/일시중단/마감)
- [ ] 지표 카드(총 재고/잔여/대기열 인원/예매수/매출/취소/Consumer Lag)
- [ ] 재고/대기열 실시간 상태 바
- [ ] 최근 주문/처리 로그
- [ ] 동시 입장 허용 인원(throughput) 설정
- [ ] 정합성 상태(재고 vs 주문)

## 연결
- 주문 로그 → /admin/orders
