package com.flowticket.order.repository;

import com.flowticket.order.domain.Payment;
import com.flowticket.order.domain.PaymentStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** 멱등: 같은 결제 시도(키)가 이미 있으면 그 결과 재사용. */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /** 무통장 입금 확인 대상 — 주문의 최신 READY 결제(가상계좌 발급분). */
    Optional<Payment> findFirstByOrderIdAndStatusOrderByIdDesc(Long orderId, PaymentStatus status);
}
