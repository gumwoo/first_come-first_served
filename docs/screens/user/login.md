# U-LOGIN · 로그인

- ref: `assets/screens/로그인.png`
- route: `/login`
- slice: S01

## 목적
예매를 위해 계정으로 로그인. 자체 이메일 로그인 + 카카오/네이버 소셜.

## 상태(states)
- `default`
- `error` — 이메일/비번 불일치 안내

## 사용 API
- `POST /auth/login` → accessToken/refreshToken
- `GET /oauth2/authorization/{kakao|naver}`

## 화면 요소 (DoD 체크리스트)
- [ ] 이메일 / 비밀번호 입력 + "로그인 상태 유지"
- [ ] 로그인 버튼(primary)
- [ ] 카카오로 계속하기 / 네이버로 계속하기 버튼
- [ ] 비밀번호 찾기 / 회원가입 링크
- [ ] 우측 안내 박스(보안 로그인 안내)

## 연결
- 회원가입 → /signup
- 로그인 성공 → 직전 페이지 또는 /
