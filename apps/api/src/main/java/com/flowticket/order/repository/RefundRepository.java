package com.flowticket.order.repository;

import com.flowticket.order.domain.Refund;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    /** 멱등: 같은 환불 시도(키)가 이미 있으면 그 결과 재사용. */
    Optional<Refund> findByIdempotencyKey(String idempotencyKey);
}
