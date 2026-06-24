# D-LOADTEST-SCENARIO · 부하테스트 시나리오 설정

- ref: `assets/screens/개발자_부하테스트 시나리오 설정.png`
- route: `/dev/loadtest/new`
- slice: S08

## 목적
k6 부하테스트 시나리오 구성(대상 이벤트/동시성/시나리오 타입/임계 기준).

## 사용 API
- `POST /dev/loadtest` (시나리오 → k6 실행 트리거)

## 화면 요소 (DoD 체크리스트)
- [ ] 환경 선택(DEV/STAGING) 배지
- [ ] 시나리오 타입 탭(스파이크/램프업/지속부하/매진경합/실패주입 등)
- [ ] 대상 이벤트 / VU(가상유저) / 지속시간 / 목표 TPS
- [ ] 검증 임계 기준(p95 응답시간/에러율) 설정
- [ ] 최근 저장 시나리오 목록
- [ ] "테스트 실행" 버튼

## 연결
- 실행 → /dev/loadtest/:id/running
