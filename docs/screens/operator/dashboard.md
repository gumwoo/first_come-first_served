# O-DASHBOARD · 운영 대시보드

- ref: `assets/screens/운영자_운영 대시보드.png`
- route: `/admin`
- slice: S07

## 목적
운영 전반 핵심 지표 한눈에. 진행중 이벤트/매출/예매수/Consumer Lag 등.

## 사용 API
- `GET /admin/dashboard`

## 화면 요소 (DoD 체크리스트)
- [ ] 기간 필터 + 이벤트 선택
- [ ] 상단 지표 카드(진행 이벤트 수/예매자/매출/에러/Consumer Lag)
- [ ] 진행중 이벤트 목록(예매율/잔여)
- [ ] 최근 이벤트/주문 리스트
- [ ] 매출 추이 차트
- [ ] 시스템 상태 요약(Redis/Kafka/API)

## 연결
- 이벤트 → /admin/events/:id · 주문 → /admin/orders
