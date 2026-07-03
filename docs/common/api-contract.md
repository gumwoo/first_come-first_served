# Common · API Contract

REST 기준. 인증은 `Authorization: Bearer <accessToken>`. 에러는 공통 포맷.

## 공통 응답/에러
```json
// 성공
{ "data": { ... } }
// 에러
{ "error": { "code": "QUEUE_EXPIRED", "message": "대기시간이 만료되었습니다." } }
```
주요 에러코드: `SOLD_OUT`, `QUEUE_EXPIRED`, `HOLD_EXPIRED`, `PAYMENT_FAILED`,
`UNAUTHORIZED`, `FORBIDDEN`, `VALIDATION_ERROR`

## 인증 (S01)
> 토큰 전달 규약: **Access Token은 응답 body `{ accessToken }`** (클라 메모리 보관),
> **Refresh Token은 httpOnly 쿠키**로만 오간다(body에 노출하지 않음). Refresh의
> 최신/직전 값은 서버 Redis가 관리(RTR).
- `POST /auth/signup` — 가입(이름/이메일/비번/휴대폰인증/약관) → `{ data: null }`
- `POST /auth/login` — 이메일+비번+remember → body `{ accessToken }` + Set-Cookie(refresh)
- `POST /auth/refresh` — **쿠키의 refresh로 재발급** → body `{ accessToken }` + 회전된 refresh 쿠키
- `POST /auth/logout` — refresh 폐기 + access 블랙리스트 + refresh 쿠키 만료
- `GET /oauth2/authorization/{kakao|naver}` — 소셜 로그인 시작(성공 시 refresh 쿠키 후 프론트 리다이렉트)
- `GET /me` — 내 프로필 `{ id, email, name, role, provider }`
- `POST /auth/phone/request` — 휴대폰 인증번호 발송 (데모는 Mock 가능)
- `POST /auth/phone/verify` — 인증번호 확인 → 실패 시 `PHONE_VERIFICATION_FAILED`

## 공연 조회 (S02) — KOPIS 동기화 포함
- `GET /events?page=&size=&genre=&region=&status=&from=&to=` — 목록(페이징/필터). 응답 `{ items, page, size, total }`
- `GET /events/:id` — 상세. **조회 시 조회수 +1**(Redis, 비동기 부수효과). 출처 무관.
- `GET /events/popular` — **인기 공연 TOP 10 = 누적 조회수 ZSET 상위**(감쇠 없음)
- `GET /events/ranking/realtime` — **실시간 랭킹 = 지수감쇠 조회수 ZSET 상위**(휘발성)
- `GET /search?q=&genre=&region=&status=&page=&size=` — 키워드 + 장르/지역/상태 필터 검색(페이징).
  **검색 실행 시 인기검색어 카운트 +1**(Redis, 부수효과)
- `GET /search/popular-keywords` — **인기 검색어 TOP N = 검색어 ZSET 상위**
- `POST /admin/sync/kopis` — KOPIS 동기화 트리거(운영자) + `@Scheduled` 일배치
  - KOPIS 호출은 백엔드만. XML 파싱 → `events` upsert.
  - 수집 범위: 오늘~+`kopis.sync.days`(기본 90)를 31일 청크로 분할, 청크별 페이지 끝까지(`max-pages` 상한).

### 조회수/랭킹 (Redis)
- 조회 기록: `GET /events/:id` 진입 시 `ZINCRBY`로 누적·실시간 ZSET 동시 +1.
  - 중복방지: `view:dedup:{eventId}:{ip}` TTL 60s — 같은 IP 60초 내 재조회는 무시.
- 키: `event:views:total`(누적, 인기 TOP), `event:views:hot`(감쇠, 실시간), `search:keywords`(인기검색어).
- 감쇠 배치: `@Scheduled` 5분마다 `event:views:hot` 전체 score `× 0.8`(반감기 ~15분),
  score < 0.1 항목은 `ZREM`으로 정리. (인기검색어도 동일 정책 적용 가능 — 단계적)

## 대기열 (S03) — Redis Sorted Set
- `POST /events/:id/queue/token` — 대기열 진입, 토큰 발급(회원). user 키 SET NX로 1인1토큰 원자 보장
- `DELETE /queue/token?token=` — 이탈(나가기): 대기 제거 / 입장 슬롯 반환
- `GET /queue/status?token=` — `{ rank, total, etaSeconds, status }`
  - status: `WAITING | ADMITTED | EXPIRED`
- `GET /sse/queue/:token` — 실시간 push(queue.admitted/queue.expired), 폴링과 이중화
- 입장 허용 시 좌석 선택 페이지로. 토큰 TTL 만료 → `QUEUE_EXPIRED`

## 실시간 푸시 (SSE) — events.yaml 이벤트를 프론트로 전송
- `GET /sse/queue/:token` — 대기열 실시간(`queue.admitted`/`queue.expired`)
- `GET /sse/orders/:id` — 주문/결제 실시간(`order.paid`/`order.failed`/`payment.vbank.deposited`/`seat.hold.expired`)
- 폴링(`GET /queue/status`)은 SSE 미지원 환경의 폴백.

## 좌석·재고 (S04) — Redis 재고 원자적 차감
- 좌석 등급: `SeatGrade`(VIP/R/S/A), 등급별 가격은 이벤트별로 정의(KOPIS엔 없음)
- `GET /events/:id/seats` — 좌석맵/등급별 잔여
- `POST /events/:id/seats/hold` — 좌석 선점(HOLD, TTL ~5분) → `holdId`
  - 재고 0 → `SOLD_OUT`
- `DELETE /seats/hold/:holdId` — 선점 해제
- HOLD TTL 만료 → `HOLD_EXPIRED`

## 주문·결제 (S05) — Kafka 주문 이벤트
- `POST /orders` — holdId로 주문 생성 → `orderId`, 상태 `PENDING`
- `POST /orders/:id/payments` — 결제수단(card/easy/vbank) 처리
  - 결제 상태머신: `PENDING → PAID | FAILED | EXPIRED`
  - 무통장: `VBANK_WAITING`(가상계좌+입금기한) → 입금확인 시 `PAID`
- `GET /orders/:id` — 주문/결제 상태
- 결제 성공 → 주문확정 이벤트를 Kafka 발행 → 티켓(QR) 발급

## 마이 / 취소·환불 (S06)
- `GET /me/orders?status=` — 예매 내역(탭: 전체/예정/사용완료/취소)
- `GET /me/orders/:id` — 상세
- `POST /me/orders/:id/refund` — 취소·환불(수수료 계산 후 환불예정액)

## 운영 (S07)
- `GET /admin/dashboard` — 지표(매출/예매수/Consumer Lag 등)
- `GET /admin/orders?...` — 주문/예매 내역(필터·페이징)
- `GET /admin/events` `POST /admin/events` `GET/PATCH /admin/events/:id`
- `GET /admin/dlq` — DLQ 메시지 목록(payload 포함)
- `POST /admin/dlq/:id/retry` — 재처리 / `POST /admin/dlq/:id/discard`
- `GET/PUT /admin/alerts` — 임계치(Lag/응답시간/에러율) + 채널(슬랙/이메일/웹훅)

## 부하·모니터링 (S08)
- `POST /dev/loadtest` — 시나리오로 k6 실행 트리거
- `GET /dev/loadtest/:id` — 실행상태(RUNNING/도달TPS/경과)
- `GET /dev/loadtest/:id/result` — 결과(PASS/FAIL, p95, 처리량…)
- `GET /dev/loadtest/report?ids=` — 비교 리포트
- `GET /dev/monitoring` — Actuator/Micrometer 기반(API 응답·Lag·인프라 상태)
