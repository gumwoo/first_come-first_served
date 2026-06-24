# D-MONITORING · 시스템 모니터링

- ref: `assets/screens/개발자_시스템 모니터링.png`
- route: `/dev/monitoring`
- slice: S08

## 목적
운영/실시간 시스템 상태(API 응답·Consumer Lag·인프라 헬스) 모니터링.

## 사용 API
- `GET /dev/monitoring` (Actuator/Micrometer 기반)

## 화면 요소 (DoD 체크리스트)
- [ ] 기간/환경 필터
- [ ] 상단 지표 카드(요청수/평균응답/p95/에러율/Consumer Lag)
- [ ] API 응답시간 추이 차트
- [ ] 시스템 요약(Redis/Kafka/DB/API 헬스 상태)
- [ ] 최근 에러/이벤트 로그
- [ ] 인프라/장비 상태 테이블

## 연결
- 알림 임계치 → /admin/alerts
