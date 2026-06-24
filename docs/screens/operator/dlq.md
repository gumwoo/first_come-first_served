# O-DLQ · DLQ 재처리 센터

- ref: `assets/screens/운영자_DLQ 재처리 센터.png`
- route: `/admin/dlq`
- slice: S07

## 목적
Kafka 처리 실패 메시지(DLQ) 조회 + 재처리/폐기.

## 사용 API
- `GET /admin/dlq?topic=&type=&from=&to=`
- `POST /admin/dlq/:id/retry` / `POST /admin/dlq/:id/discard`

## 화면 요소 (DoD 체크리스트)
- [ ] 필터 바(토픽/메시지타입/사유/기간)
- [ ] 상단 집계(전체/재처리 대기/재처리 성공/폐기/신규)
- [ ] DLQ 테이블(메시지ID/토픽/타입/사유/재시도횟수/시각/상태)
- [ ] 선택 메시지 원본 Payload 뷰(JSON)
- [ ] 원인/오류 상세 패널
- [ ] 재처리 / 폐기 / 일괄 재처리 버튼

## 연결
- (자체 처리)
