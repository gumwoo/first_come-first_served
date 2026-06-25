# Domain Rules (선착순 티켓팅 불변식)

비즈니스 불변식. 코드 위치는 service/domain. ★는 enum 전이로 하네스 일부 검증.

## 1. 재고 / 좌석
- 좌석 잔여는 **0 미만 불가**. 차감은 원자적(Redis DECR / DB 비관락).
- 좌석 HOLD는 TTL(약 5분). 만료 시 자동 반환되어 재고 복구.
- 매진(잔여 0) 상태에서 신규 주문 생성 금지 → `SOLD_OUT`.
- 좌석은 등급(`SeatGrade`: VIP/R/S/A)을 가지며, **등급별 가격은 이벤트별로 정의**.
  재고·가격은 우리 DB 기준(KOPIS엔 없음).

## 1-1. 휴대폰 인증 (회원가입)
- 회원가입은 휴대폰 인증 완료가 선행되어야 함 → 미완료 시 `PHONE_VERIFICATION_REQUIRED`.
- 인증번호 불일치/만료 → `PHONE_VERIFICATION_FAILED`. (데모는 Mock 검증 허용)

## 1-2. 실시간 전송
- 대기열 입장/만료, 결제 완료/실패, 선점 만료는 SSE로 프론트에 push.
- events.yaml의 `required_fe_subscribes`는 SSE로 반드시 전달되어야 하는 이벤트.

## 2. 대기열
- 한 사용자는 이벤트당 활성 대기 토큰 1개.
- 토큰 상태: `WAITING → ADMITTED → (좌석선택)` / TTL 만료 시 `EXPIRED`.
- ADMITTED 안 된 토큰으로 좌석/주문 접근 금지.

## 3. 주문 / 결제 상태 전이 ★
허용 전이만 가능(그 외 예외):
```
PENDING → PAID
PENDING → FAILED
PENDING → VBANK_WAITING → PAID | EXPIRED
PAID    → CANCELLED → REFUNDED
PENDING → EXPIRED            (결제시간 초과)
```
- PAID 외 상태에서 티켓(QR) 발급 금지.
- 결제 성공 시에만 `order.paid` 이벤트 발행.

## 4. 구매 제한
- 1인 1주문 좌석 수 제한(이벤트별 maxPerUser).
- 동일 회차 중복 예매 제한.

## 5. 환불
- 취소는 PAID 상태에서만. 환불액 = 결제액 − 기간별 수수료.
- 환불 완료 시 좌석 재고 복구 + `REFUNDED`.

## 6. 정합성
- 재고 = 총좌석 − (PAID + HOLD중) 이어야 함. 어긋나면 운영 알림.
- 모든 enum 값은 `contracts/enums.yaml`에 등록되어야 함 ★.
