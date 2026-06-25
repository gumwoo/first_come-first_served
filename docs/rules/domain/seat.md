# Domain · S04 좌석 / 재고

## 재고 / 좌석
- 좌석 잔여는 **0 미만 불가**. 차감은 원자적(Redis DECR / DB 비관락). [T]
- 좌석 HOLD는 TTL(약 5분). 만료 시 자동 반환되어 재고 복구. [T]
- 매진(잔여 0) 상태에서 신규 주문 생성 금지 → `SOLD_OUT`. [T]
- 좌석은 등급(`SeatGrade`: VIP/R/S/A)을 가지며, **등급별 가격은 이벤트별로 정의**.
  재고·가격은 우리 DB 기준(KOPIS엔 없음). [R]

## 횡단 참조
- 재고 정합성(재고 = 총좌석 − (PAID + HOLD))은 [domain-rules.md](../domain-rules.md) 횡단 불변식.
