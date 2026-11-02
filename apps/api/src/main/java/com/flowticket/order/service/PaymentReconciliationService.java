package com.flowticket.order.service;

import com.flowticket.global.config.SchedulingConfig;
import com.flowticket.order.domain.Order;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.gateway.PaymentGateway;
import com.flowticket.order.gateway.PaymentGateway.ApproveResult;
import com.flowticket.order.gateway.PaymentGateway.Inquiry;
import com.flowticket.order.repository.OrderRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 결제 정산·보상(ADR-011). 외부 PG ↔ 내부 주문 상태의 불일치를 주기적으로 잡는다.
 *
 * 아웃박스(ADR-010)는 "DB에 커밋된 사실"을 밖으로 전달하는 패턴이라 이 문제를 못 닫는다.
 * PG 승인은 성공했는데 좌석 확정 실패·프로세스 크래시로 트랜잭션이 롤백되면 payments 행까지
 * 사라져 DB에 흔적 자체가 없기 때문이다. 그래서 흔적이 남는 주문에서 후보를 만들고,
 * PG에 직접 조회해 "우리는 미확정인데 PG엔 승인"인 미아 승인을 찾아 취소(void)한다.
 *
 * finalizePaid의 즉시 보상(TS-011 §7)이 1차 방어이고, 이 잡은 그 보상마저 실행되지 못한
 * 크래시 구간의 안전망이다. 주문이 이미 PAID인 경우는 후보가 아니다. PAID는 승인이 남아 있는 게 정상이라
 * 조회 결과만으로 그 승인이 늦게 온 다른 승인인지 가르지 못한다(PaymentService.compensateApproval).
 */
@Slf4j
@Service
public class PaymentReconciliationService {

    /** 결제로 확정되지 않은 상태 = 이 주문에 PG 승인이 남아 있으면 안 되는 상태. */
    private static final List<OrderStatus> UNSETTLED =
            List.of(OrderStatus.PENDING, OrderStatus.EXPIRED);

    private final OrderRepository orderRepository;
    private final PaymentGateway gateway;
    private final int graceMinutes;
    private final int lookbackHours;
    private final int batchSize;
    private final int recheckMinutes;

    private final Clock clock;

    public PaymentReconciliationService(OrderRepository orderRepository, PaymentGateway gateway,
                                        @Value("${payment.reconcile-grace-minutes:10}") int graceMinutes,
                                        @Value("${payment.reconcile-lookback-hours:24}") int lookbackHours,
                                        @Value("${payment.reconcile-batch-size:50}") int batchSize,
                                        @Value("${payment.reconcile-recheck-minutes:60}") int recheckMinutes,
                                        Clock clock) {
        this.clock = clock;
        this.orderRepository = orderRepository;
        this.gateway = gateway;
        this.graceMinutes = graceMinutes;
        this.lookbackHours = lookbackHours;
        this.batchSize = batchSize;
        this.recheckMinutes = recheckMinutes;
    }

    /**
     * 미아 승인 정산. 트랜잭션을 걸지 않는다. 우리 DB에는 조회 표시만 남기고, 건별로 외부 PG
     * 호출(조회·취소)을 하므로 네트워크 I/O가 DB 트랜잭션을 오래 붙잡으면 안 된다.
     *
     * 한 틱은 배치 크기만큼만 보고, 본 주문에는 조회 시각을 남겨 다음 틱이 그다음 묶음으로 넘어가게 한다.
     */
    @Scheduled(fixedRateString = "${payment.reconcile-interval-ms:600000}",
               initialDelayString = "${payment.reconcile-interval-ms:600000}",
               scheduler = SchedulingConfig.EXTERNAL_IO)
    @SchedulerLock(name = "payment-reconcile", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void reconcileOrphanApprovals() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Order> candidates = orderRepository.findReconcileCandidates(
                UNSETTLED,
                now.minusMinutes(graceMinutes), // 유예: 진행 중인 결제를 건드리지 않음
                now.minusHours(lookbackHours),  // 소급 한계: 오래된 건은 수동 정산 대상
                now.minusMinutes(recheckMinutes), // 재조회 하한: 방금 본 주문은 건너뛴다
                PageRequest.of(0, batchSize));
        if (candidates.isEmpty()) {
            return;
        }
        int compensated = 0;
        for (Order order : candidates) {
            if (compensate(order)) {
                compensated++;
            }
        }
        // 조회·취소에 실패한 건까지 포함해 표시한다. 실패를 표시하지 않으면 그 주문이 매 틱 선두로
        // 돌아와 뒤쪽 후보를 다시 굶긴다. 재조회 간격이 지나면 다시 시도된다.
        orderRepository.markReconcileChecked(candidates.stream().map(Order::getId).toList(), now);
        log.info("[reconcile] 후보 {}건 중 미아 승인 {}건 취소", candidates.size(), compensated);
    }

    /** 미아 승인이면 취소하고 true. 조회/취소 실패는 삼키고 재조회 간격 뒤에 다시 시도한다(주문이 후보로 남음). */
    private boolean compensate(Order order) {
        try {
            Inquiry inquiry = gateway.inquire(order.getId());
            if (!inquiry.approved()) {
                return false; // 정상: 승인 없음(또는 이미 취소됨)
            }
            ApproveResult cancelled = gateway.refund(inquiry.pgTid(), order.getAmount(),
                    "void-" + inquiry.pgTid());
            if (!cancelled.success()) {
                log.error("[reconcile] 미아 승인 취소 실패 orderId={} pgTid={} 사유={}",
                        order.getId(), inquiry.pgTid(), cancelled.failReason());
                return false;
            }
            // 주문은 이미 미확정(PENDING/EXPIRED)이고 좌석·홀드도 풀린 상태라 추가 상태 전이는 없다.
            log.warn("[reconcile] 미아 승인 취소 orderId={} status={} pgTid={} amount={}",
                    order.getId(), order.getStatus(), inquiry.pgTid(), order.getAmount());
            return true;
        } catch (RuntimeException e) {
            log.warn("[reconcile] 정산 실패 orderId={}: {}", order.getId(), e.toString());
            return false;
        }
    }
}
