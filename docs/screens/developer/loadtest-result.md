# D-LOADTEST-RESULT · 부하테스트 결과 상세

- ref: `assets/screens/개발자_부하테스트 결과 상세.png`
- route: `/dev/loadtest/:id/result`
- slice: S08

## 목적
완료된 테스트 결과 상세 + PASS/FAIL 판정.

## 사용 API
- `GET /dev/loadtest/:id/result`

## 화면 요소 (DoD 체크리스트)
- [ ] 판정 배지(PASS/FAIL) + 총 요청/처리량/에러율
- [ ] 지표 카드(TPS/p50·p95·p99 응답시간/에러율/Consumer Lag)
- [ ] 응답시간·처리량 차트
- [ ] 검증 임계 대비 결과 표(기준 vs 실제 + 통과여부)
- [ ] 실패/에러 분류 테이블
- [ ] 리포트로 비교 추가

## 연결
- 비교 → /dev/loadtest/report
