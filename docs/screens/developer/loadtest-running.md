# D-LOADTEST-RUNNING · 부하테스트 실행 상태

- ref: `assets/screens/개발자_부하테스트 실행 상태.png`
- route: `/dev/loadtest/:id/running`
- slice: S08

## 목적
진행 중 부하테스트 실시간 모니터링(RUNNING).

## 사용 API
- `GET /dev/loadtest/:id` (폴링: 상태/도달TPS/경과/실시간 지표)

## 화면 요소 (DoD 체크리스트)
- [ ] 상태 배지(RUNNING) + 경과 시간
- [ ] 실시간 지표 카드(요청수/성공/실패/현재TPS/VU)
- [ ] 실시간 TPS/응답시간 라인 차트
- [ ] 실행 로그 스트림
- [ ] 우측 실시간 시스템 상태(Redis/Kafka/Lag/결제)
- [ ] 중지 버튼

## 연결
- 완료 → /dev/loadtest/:id/result
