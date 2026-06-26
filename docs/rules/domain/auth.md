# Domain · S01 인증 / 가입

선착순 티켓팅에서 인증/가입은 단순 CRUD가 아니라 **계정 유일성, 소셜/로컬 분리,
휴대폰 기반 다계정 방지, Refresh Token Rotation** 같은 보안 불변식이 핵심이다.
정적으로 판별 가능한 것(enum/엔드포인트/에러코드 존재)은 하네스가, 실행으로만
확인되는 것(중복가입/TTL/토큰회전/블랙리스트)은 테스트가 검증한다.

---

## 1. 계정 및 가입 불변식

### 이메일 전역 유일성
- `email`은 시스템 전체에서 유일. 이미 가입된 이메일로 가입 시도 → `DUPLICATE_EMAIL`. [T]
- 로컬 가입·소셜 가입 모두 포함. 같은 이메일로 로컬/소셜 중복 생성 불가.

### 비밀번호 정책
- 비밀번호는 **영문 + 숫자 + 특수문자를 모두 포함하고 8자 이상**이어야 한다. [T]
- 검증의 단일 진실원은 **서버**(`SignupRequest`). 프론트는 동일 규칙을 안내·실시간
  표시하되, 최종 강제는 백엔드가 한다. 위반 시 `VALIDATION_ERROR`.
- 저장은 BCrypt 단방향 해시(평문 금지).

### 로그인 실패 메시지 (account enumeration 방지)
- 이메일 없음/비밀번호 불일치를 **구분하지 않고** 동일하게 `INVALID_CREDENTIALS`
  ("이메일 또는 비밀번호가 올바르지 않습니다")로 응답한다. [T]
- 어느 쪽이 틀렸는지 노출하면 가입 이메일을 추측당할 수 있으므로 통합 메시지를 쓴다.

### 소셜 계정 비밀번호 격리
- `provider`가 소셜(`kakao`/`naver`)인 사용자는 `passwordHash`가 반드시 `null`. [T]
- 소셜 계정으로 `POST /auth/login`(이메일/비번) 또는 비밀번호 변경 시도 →
  `LOCAL_LOGIN_NOT_ALLOWED`. [T]

### 교차 가입 방지
- 동일 이메일로 소셜 계정이 이미 있는데 로컬 가입 시도 → 차단(기존 계정 덮어쓰기 금지).
  계정 연결 정책 구현 전까지 로컬/소셜 교차 가입 불허.
- 본 프로젝트에서는 중복 이메일 정책으로 보고 `DUPLICATE_EMAIL` 반환. [T]

### 가입 권한 고정 (권한 상승 방지)
- 가입 API로 생성되는 신규 사용자의 `UserRole`은 반드시 `ROLE_USER`. [T]
- 가입 DTO에 `role`/`roles`/`authority`가 와도 **서버는 무시**하고 `ROLE_USER` 강제.
- 요청 바디를 통한 권한 상승 공격을 막는 불변식.

### 필수 약관 동의
- 필수 약관 동의가 `false`면 가입 거절 → `REGISTRATION_TERMS_NOT_ACCEPTED`. [T]
- 프론트가 동의 UI를 제공해도 **최종 검증은 백엔드에서**.

---

## 2. 휴대폰 인증 및 어뷰징 차단

> 정책 주: 암표상·다계정 어뷰징을 줄이기 위해 **이 프로젝트 정책상** 1개의 휴대폰
> 번호가 1개의 계정에만 연결되도록 제한한다(가족/법인/번호변경 케이스는 범위 외).

### 1폰 1계정
- `phone`은 DB UNIQUE. 소셜/로컬 불문, 이미 가입된 번호로 가입 시도 → `DUPLICATE_PHONE`. [T]

### 인증 선행 필수
- 회원가입은 휴대폰 인증 성공 정보가 선행되어야 함.
  인증 없이 `POST /auth/signup` 직접 호출 → `PHONE_VERIFICATION_REQUIRED`. [T]
- 프론트 화면 순서와 무관하게 백엔드가 Redis 인증 성공 플래그로 확인.

### 인증번호 수명 및 검증
- 인증번호는 Redis 저장, TTL **3분**. 만료 후 검증 또는 불일치 → `PHONE_VERIFICATION_FAILED`. [T]
- 데모는 외부 SMS 없이 Mock 인증 허용. 운영은 실제 SMS + Redis TTL 검증.

### 인증 세션의 1회성
- 인증 성공 시 Redis에 성공 플래그 저장, 유효기간 **최대 10분**.
- 가입 최종 성공 시 성공 플래그 즉시 삭제. 사용된 플래그 재사용 불가. [T]

### 문자 발송 제한
- 동일 번호 인증번호 발송은 **1시간 5회**까지. 초과 → `PHONE_VERIFICATION_LIMIT_EXCEEDED`. [T]
- SMS 비용 폭탄·발송 API 남용·자동 다계정 시도 방지.

---

## 3. 토큰 생명주기 / Refresh Token Rotation

### Access Token
- 서버가 별도 세션 상태를 두지 않고 **JWT 서명 + 만료로 stateless 검증**.
- 탈취 시 피해 범위를 줄이기 위해 수명 **30분 이내**.
- **모든 토큰은 `type` claim(access/refresh)을 가진다** ★. 필터는 access만, 회전은
  refresh만 허용해 토큰 오용(refresh를 access로 사용 등)을 차단한다. (하네스 검사)

### 관측성 노출
- actuator는 `health`/`info`만 공개하고 metrics/prometheus 등은 인증 필요 ★(정보 노출 방지).

### Refresh Token Rotation
- `POST /auth/refresh` 성공 시 요청에 쓰인 Refresh Token은 즉시 사용 불가가 됨.
- 새 Refresh Token 발급 + Redis 최신 토큰 정보 갱신.
- 하나의 Refresh Token은 **1회 재발급에만** 사용 가능. [T]

### 재사용 탐지
- 이미 사용·폐기된 Refresh Token으로 재발급 요청이 오면 **탈취 가능성**으로 간주.
- 해당 사용자의 Redis 토큰 패밀리를 모두 삭제하고 재로그인을 요구 → `REFRESH_TOKEN_REUSED`. [T]

### 유효하지 않은 Refresh Token
- Redis에 없거나 서명 무효/만료 → 재발급 거절.
  `INVALID_REFRESH_TOKEN` 또는 `REFRESH_TOKEN_EXPIRED`. [T]

### CSRF 전략
- 일반 API는 Bearer 헤더 기반 stateless → CSRF 무관(전면 disable 유지).
- 쿠키가 자동 전송되는 엔드포인트는 `/auth/refresh`·`/auth/logout` 뿐.
  이들은 Refresh 쿠키 `SameSite=Lax`로 1차 방어한다.
- 운영 전환 시: 쿠키를 `SameSite=Strict`로 강화하거나, 쿠키 기반 경로에
  한해 CSRF 토큰 전략을 도입한다. [R]

### 로그인 상태 유지 (Remember-me)
- Refresh Token은 **httpOnly 쿠키**로 클라이언트에 전달한다(본문 노출 금지, XSS 방어). [T]
- 로그인 시 "로그인 상태 유지" 체크 → **영구 쿠키**(maxAge=refresh TTL),
  미체크 → **세션 쿠키**(브라우저 종료 시 만료).
- 앱 로드 시 프론트는 `POST /auth/refresh`(쿠키 자동 전송)로 Access Token을 silent 재발급한다.
- Access Token은 여전히 응답 본문으로 내려가며 클라 메모리에만 보관한다.
- **remember 값은 Refresh 토큰 claim으로 보존**한다. 회전(refresh) 시에도 원래
  remember를 따라 쿠키 maxAge(세션/영속)를 유지한다 → silent refresh가 세션 쿠키를
  영속 쿠키로 승격시키지 않는다. [T]

### 마케팅 수신 동의
- 회원가입의 이벤트/혜택 알림(선택 약관) 동의 여부는 `users.marketing_opt_in`에 저장. [T]
- 선택 약관 미동의로도 가입은 가능하다(필수 약관만 가입 전제).

### 로그아웃 / Access Token 블랙리스트
- `POST /auth/logout` 시 Redis의 Refresh Token 삭제.
- 현재 Access Token의 **남은 TTL 동안** 토큰 값을 Redis 블랙리스트에 등록.
- 블랙리스트 토큰으로 보호 API 접근 → `ACCESS_TOKEN_BLACKLISTED`. [T]
- 로그아웃 이후 동일 토큰 재사용을 차단(분산 환경 변수로 100% 보장은 아님).

---

## 4. 검증 책임 분리

### 하네스(정적)로 검증
- `UserRole` 등 enum 값 계약 / API endpoint 존재
- 표준 에러코드가 `contracts/error-codes.yaml`에 등록되었는지
- 금지 의존성·계층 위반·금지 패턴·설정파일 시크릿

### 테스트로 검증
- 중복 이메일 → `DUPLICATE_EMAIL`, 중복 폰 → `DUPLICATE_PHONE`
- 소셜 유저 `passwordHash` null / 소셜의 로컬 로그인·비번변경 차단
- 가입 시 `ROLE_USER` 강제 / 약관 미동의 거절
- 인증 없이 가입 불가 / 인증번호 TTL 3분 / 성공 플래그 가입 후 삭제 / 발송 5회 제한
- RTR 동작 / 재사용 토큰 탐지 시 토큰 패밀리 삭제 / 로그아웃 블랙리스트
