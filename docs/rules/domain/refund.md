# Domain · S06 취소 / 환불

- 취소는 `PAID` 상태에서만 가능. 그 외 → `REFUND_NOT_ALLOWED`. [T]
- 환불액 = 결제액 − 기간별 수수료. (수수료 정책은 이벤트/기간별 정의) [R]
- 환불 완료 시 좌석 재고 복구 + 상태 `REFUNDED`. [T]

## 횡단 참조
- 재고 복구 후 정합성(재고 = 총좌석 − (PAID + HOLD))은 domain-rules.md 횡단 불변식.
