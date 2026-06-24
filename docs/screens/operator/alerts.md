# O-ALERTS · 알림 / 임계치 설정

- ref: `assets/screens/운영자_알림 임계치 설정.png`
- route: `/admin/alerts`
- slice: S07

## 목적
운영 지표 임계치(Consumer Lag/응답시간/에러율 등)와 알림 채널 설정.

## 사용 API
- `GET /admin/alerts` / `PUT /admin/alerts`

## 화면 요소 (DoD 체크리스트)
- [ ] 상단 요약(활성 알림 규칙/발생 건/채널 수)
- [ ] 알림 규칙 테이블(지표/조건/임계값/심각도/on-off 토글)
- [ ] 알림 채널 설정(슬랙/이메일/웹훅)
- [ ] 최근 알림 이력 로그
- [ ] 규칙 추가/저장

## 연결
- (자체 처리)
