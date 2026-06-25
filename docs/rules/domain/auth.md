# Domain · S01 인증 / 가입

> 상태: 기존 휴대폰 인증 규칙만 이관됨. 계정 불변식/토큰 RTR 등 S01 전체 규칙은
> S01 구현 착수 시 이 파일에 보강 예정.

## 휴대폰 인증 (회원가입)
- 회원가입은 휴대폰 인증 완료가 선행되어야 함 → 미완료 시 `PHONE_VERIFICATION_REQUIRED`. [T]
- 인증번호 불일치/만료 → `PHONE_VERIFICATION_FAILED`. (데모는 Mock 검증 허용) [T]
