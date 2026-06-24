# D-LOADTEST-REPORT · 부하테스트 리포트 / 비교

- ref: `assets/screens/개발자_부하테스트 리포트 비교.png`
- route: `/dev/loadtest/report`
- slice: S08

## 목적
두 개 이상 테스트 실행 결과 비교(개선 전후 등).

## 사용 API
- `GET /dev/loadtest/report?ids=`

## 화면 요소 (DoD 체크리스트)
- [ ] 비교 대상 2개(이상) 선택 드롭다운
- [ ] 핵심 지표 비교 카드(처리량/응답시간/에러율 — 증감 % 표시)
- [ ] 좌우 상세 지표 비교 표
- [ ] 응답시간/처리량 오버레이 차트
- [ ] 개선/회귀 요약(자동 코멘트)
- [ ] 최근 실행 이력 리스트

## 연결
- 실행 항목 → /dev/loadtest/:id/result
