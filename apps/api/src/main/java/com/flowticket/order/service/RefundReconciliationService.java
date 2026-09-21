package com.flowticket.order.service;

import com.flowticket.order.domain.Order;
import com.flowticket.order.gateway.PaymentGateway;
import com.flowticket.order.gateway.PaymentGateway.Inquiry;
import com.flowticket.order.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 환불 정산·보상(ADR-011). PG에는 취소가 남았는데 우리는 결제 완료로 아는 주문을 찾아 맞춘다.
 *
 * RefundService는 PG 취소와 DB 쓰기를 한 트랜잭션에 묶는다. PG 취소가 성공한 뒤 뒤쪽 쓰기가
 * 실패하면 DB만 롤백돼 주문이 PAID로 돌아가고, 고객은 돈을 돌려받았는데 화면에는 결제 완료로
 * 남는다. 롤백된 트랜잭션은 흔적을 남기지 않으므로 DB만 봐서는 이 상태를 알 수 없다.
 *
 * 미아 승인을 보는 PaymentReconciliationService와 방향이 반대다.
 *   미아 승인: 우리는 미확정인데 PG엔 승인 → PG를 취소한다
 *   미아 취소: 우리는 결제 완료인데 PG엔 취소 → 우리 상태를 환불로 맞춘다
 * 돈이 이미 고객에게 갔으므로 되돌릴 수 있는 쪽은 우리뿐이다.
 */
@Slf4j
@Service
public class RefundReconciliationService {

    private final OrderRepository orderRepository;
    private final PaymentGateway gateway;
    private final RefundConverger converger;
    private final int graceMinutes;
    private final int lookbackHours;
    private final int batchSize;
    private final int recheckMinutes;

    public RefundReconciliationService(OrderRepository orderRepository, PaymentGateway gateway,
                                       RefundConverger converger,
                                       @Value("${refund.reconcile-grace-minutes:10}") int graceMinutes,
                                       @Value("${refund.reconcile-lookback-hours:24}") int lookbackHours,
                                       @Value("${refund.reconcile-batch-size:50}") int batchSize,
                                       @Value("${refund.reconcile-recheck-minutes:60}") int recheckMinutes) {
        this.orderRepository = orderRepository;
        this.gateway = gateway;
        this.converger = converger;
        this.graceMinutes = graceMinutes;
        this.lookbackHours = lookbackHours;
        this.batchSize = batchSize;
        this.recheckMinutes = recheckMinutes;
    }

    /**
     * 미아 취소 정산. 트랜잭션을 걸지 않는다. 후보 조회 외에는 PG 호출이고, 수렴 쓰기는
     * RefundConverger의 짧은 트랜잭션에서 한다.
     *
     * 한 틱은 배치 크기만큼만 보고, 본 주문에는 조회 시각을 남겨 다음 틱이 그다음 묶음으로
     * 넘어가게 한다. 정상 주문은 조회해도 PAID로 남기 때문에, 표시가 없으면 같은 앞쪽 묶음만
     * 영원히 반복하고 뒤쪽은 소급 한계 밖으로 사라진다.
     *
     * 후보가 PAID 전체라 조회 비용이 결제 건수에 비례한다. 유예·소급 한계·배치 상한으로 묶지만,
     * 결제량이 커지면 환불 시도 자체를 먼저 기록하고 그 행만 후보로 삼는 구조로 바꿔야 한다.
     */
    @Scheduled(fixedRateString = "${refund.reconcile-interval-ms:600000}",
               initialDelayString = "${refund.reconcile-interval-ms:600000}")
    @SchedulerLock(name = "refund-reconcile", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void reconcileOrphanCancellations() {
        LocalDateTime now = LocalDateTime.now();
        List<Order> candidates = orderRepository.findRefundReconcileCandidates(
                now.minusMinutes(graceMinutes),   // 유예: 진행 중인 환불을 건드리지 않음
                now.minusHours(lookbackHours),    // 소급 한계: 오래된 건은 수동 정산 대상
                now.minusMinutes(recheckMinutes), // 재조회 하한: 방금 본 주문은 건너뛴다
                PageRequest.of(0, batchSize));
        if (candidates.isEmpty()) {
            return;
        }
        int converged = 0;
        for (Order order : candidates) {
            if (converge(order)) {
                converged++;
            }
        }
        // PG 조회에 실패한 건까지 포함해 표시한다. 실패를 표시하지 않으면 그 주문이 매 틱 선두로
        // 돌아와 뒤쪽 후보를 다시 굶긴다. 다음 순회에서 재시도된다.
        orderRepository.markRefundChecked(candidates.stream().map(Order::getId).toList(), now);
        log.info("[reconcile] 환불 후보 {}건 조회, {}건 수렴", candidates.size(), converged);
    }

    /**
     * PG에 취소가 남아 있으면 우리 상태를 맞추고 true.
     *
     * 취소가 아닌 모든 응답에서 아무것도 하지 않는다. 특히 UNKNOWN(조회 실패)은 정상 결제와
     * 구분되지 않으므로 여기서 멈춰야 한다. 그렇지 않으면 PG가 잠깐 흔들릴 때 멀쩡한 주문이
     * 환불 처리된다. 후보는 다음 틱에 다시 올라온다.
     */
    private boolean converge(Order order) {
        try {
            Inquiry inquiry = gateway.inquire(order.getId());
            if (!inquiry.canceled()) {
                return false;
            }
            int canceled = inquiry.canceledAmount();
            if (canceled <= 0 || canceled > order.getAmount()) {
                // 결제금액을 넘는 취소는 우리 계산으로 설명되지 않는다. 자동 수렴 대신 사람이 본다.
                log.error("[reconcile] 취소 금액이 결제금액과 맞지 않는다 orderId={} 결제={} 취소={}",
                        order.getId(), order.getAmount(), canceled);
                return false;
            }
            if (!converger.converge(order, inquiry.pgTid(), canceled)) {
                return false; // 사용자 환불이 방금 가져갔거나 이미 수렴됨
            }
            log.warn("[reconcile] PG에만 있던 환불을 반영 orderId={} pgTid={} 결제={} 취소={}",
                    order.getId(), inquiry.pgTid(), order.getAmount(), canceled);
            return true;
        } catch (RuntimeException e) {
            log.warn("[reconcile] 환불 정산 실패 orderId={}: {}", order.getId(), e.toString());
            return false;
        }
    }
}
