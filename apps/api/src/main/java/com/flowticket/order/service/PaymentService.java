package com.flowticket.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.order.domain.Order;
import com.flowticket.order.domain.OrderItem;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.domain.Payment;
import com.flowticket.order.domain.PaymentStatus;
import com.flowticket.order.dto.PaymentResponse;
import com.flowticket.order.event.OrderEvent;
import com.flowticket.order.gateway.PaymentGateway;
import com.flowticket.order.gateway.PaymentGateway.ApproveResult;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.repository.PaymentRepository;
import com.flowticket.order.sse.OrderSseRegistry;
import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.repository.OutboxEventRepository;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 승인(card/easy 즉시, vbank 가상계좌+입금확인). 게이트웨이 승인 → 조건부 주문전이 →
 * 좌석 SOLD·hold CONVERTED. 멱등: 같은 idempotencyKey는 재처리하지 않음(ADR-006).
 */
@Slf4j
@Service
public class PaymentService {

    private static final Set<String> IMMEDIATE = Set.of("card", "easy");
    /** 아웃박스 aggregate 구분자(현재는 주문 이벤트만 이관: ADR-008의 점진 이관 계승). */
    private static final String AGGREGATE_ORDER = "order";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final SeatRepository seatRepository;
    private final SeatHoldRepository holdRepository;
    private final PaymentGateway gateway;
    private final OrderSseRegistry orderSse;
    private final OutboxEventRepository outboxRepository; // 같은 tx에 이벤트 적재(ADR-010)
    private final ObjectMapper objectMapper;
    /** 트랜잭션 경계를 코드로 연다. 커밋에서 나는 멱등키 충돌을 경계 밖에서 잡아야 하기 때문이다. */
    private final TransactionTemplate tx;

    private final Clock clock;
    /** 중복 요청이 승자의 최종 결과를 기다리는 상한. PG 응답 시간(기본 10초)보다 짧게 잡는다. */
    private final long duplicateWaitMs;

    /** 승자 상태 재확인 간격. 더블클릭 한 번에 몇 번 더 읽는 정도라 짧게 둔다. */
    private static final long DUPLICATE_POLL_MS = 50;

    public PaymentService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                          PaymentRepository paymentRepository, SeatRepository seatRepository,
                          SeatHoldRepository holdRepository, PaymentGateway gateway,
                          OrderSseRegistry orderSse, OutboxEventRepository outboxRepository,
                          ObjectMapper objectMapper, TransactionTemplate tx, Clock clock,
                          @Value("${payment.duplicate-wait-ms:5000}") long duplicateWaitMs) {
        this.clock = clock;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentRepository = paymentRepository;
        this.seatRepository = seatRepository;
        this.holdRepository = holdRepository;
        this.gateway = gateway;
        this.orderSse = orderSse;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.tx = tx;
        this.duplicateWaitMs = duplicateWaitMs;
    }

    /**
     * 결제 진입.
     *
     * 세 구간으로 나뉜다. PG 호출을 DB 트랜잭션 밖으로 빼기 위해서다(ADR-020).
     *   TX1  검증 + READY 결제 행 커밋   — 시도가 남는다
     *   (밖) 승인 요청                   — 커넥션을 쥐지 않는다
     *   TX2  승인 확정(주문·좌석·홀드·아웃박스)
     *
     * 동시 같은 idempotencyKey(더블클릭)로 UNIQUE 충돌이 나면 이미 다른 스레드가 처리한 것이므로
     * 기존 결과를 멱등하게 반환한다(이중 PAID/발급 0, IMP-008).
     */
    public PaymentResponse pay(Long userId, Long orderId, String method, String provider, String idemKey) {
        if (idemKey == null || idemKey.isBlank() || method == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            Started started = tx.execute(status -> startPayment(userId, orderId, method, provider, idemKey));
            if (started.duplicate() != null) {
                return started.duplicate();
            }
            if ("vbank".equals(method)) {
                // 발급도 외부 호출이다. 계좌를 받아 온 뒤 별도 트랜잭션에서 확정한다.
                PaymentGateway.VbankIssue vb = gateway.issueVbank(orderId, started.amount());
                return tx.execute(status -> assignVbankTx(started.paymentId(), orderId, vb));
            }
            ApproveResult res = gateway.approve(orderId, started.amount(), method, provider, idemKey);
            return settle(started.paymentId(), orderId, started.amount(), res, OrderStatus.PENDING);
        } catch (DataIntegrityViolationException e) {
            return awaitWinner(idemKey, orderId);
        }
    }

    /**
     * 결제창(Toss 등) 인증 후 서버 확정. 클라이언트가 받은 paymentKey로 승인 API를 호출한다.
     * 멱등키는 결제창의 paymentKey(주문당 유일)를 사용: 동시/재요청 시 UNIQUE로 이중 승인 차단.
     * 구간 분할은 pay()와 같다.
     */
    public PaymentResponse confirm(Long userId, Long orderId, String paymentKey) {
        if (paymentKey == null || paymentKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            Started started = tx.execute(status -> startPayment(userId, orderId, "card", "toss", paymentKey));
            if (started.duplicate() != null) {
                return started.duplicate();
            }
            ApproveResult res = gateway.confirm(orderId, paymentKey, started.amount());
            return settle(started.paymentId(), orderId, started.amount(), res, OrderStatus.PENDING);
        } catch (DataIntegrityViolationException e) {
            return awaitWinner(paymentKey, orderId);
        }
    }

    /**
     * 같은 멱등키의 동시 요청(더블클릭)이 승자의 최종 결과를 받게 한다(IMP-008).
     *
     * 승인을 트랜잭션 밖으로 빼면서 생긴 구간 때문에 필요하다. 패자가 UNIQUE에 걸리는 시점에
     * 승자는 아직 PG 응답을 기다리는 중이라, 그 순간의 결제 행은 READY다. 그대로 돌려주면
     * "같은 요청은 같은 답을 받는다"가 깨진다.
     *
     * 기다리는 시간 자체는 예전과 같다. 승인까지 한 트랜잭션이던 때도 패자는 UNIQUE 인덱스에서
     * 승자의 커밋(= PG 응답 이후)까지 블록됐다. 달라진 것은 기다리는 동안 DB 커넥션을 쥐지
     * 않는다는 점이다. 상한을 넘기면 진행 중 상태를 그대로 돌려준다(무한 대기보다 낫다).
     */
    private PaymentResponse awaitWinner(String idemKey, Long orderId) {
        long deadline = System.nanoTime() + duplicateWaitMs * 1_000_000L;
        while (true) {
            Payment winner = paymentRepository.findByIdempotencyKey(idemKey)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
            if (winner.getStatus() != PaymentStatus.READY || System.nanoTime() >= deadline) {
                if (winner.getStatus() == PaymentStatus.READY) {
                    log.warn("[payment] 중복 요청이 승자를 기다리다 상한 초과 orderId={} key={}", orderId, idemKey);
                }
                return PaymentResponse.of(winner.getId(), winner.getStatus().name(),
                        currentStatus(orderId).name());
            }
            try {
                Thread.sleep(DUPLICATE_POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return PaymentResponse.of(winner.getId(), winner.getStatus().name(),
                        currentStatus(orderId).name());
            }
        }
    }

    /** TX1의 결과. 이미 같은 멱등키로 처리된 요청이면 duplicate에 그 결과가 담긴다. */
    private record Started(Long paymentId, int amount, PaymentResponse duplicate) {}

    /**
     * TX1: 주문을 검증하고 READY 결제 행을 만든다.
     *
     * 이 행이 커밋돼야 PG 호출 직후 프로세스가 죽어도 "시도했다"는 사실이 남는다. 예전에는 승인과
     * 한 트랜잭션이라 롤백되면 행까지 사라져, 미아 승인을 주문 상태로만 추적해야 했다(ADR-011).
     */
    private Started startPayment(Long userId, Long orderId, String method, String provider, String idemKey) {
        Order order = ownedOrder(orderId, userId);

        // 멱등: 같은 결제 시도가 이미 있으면 그 결과 반환(순차 더블클릭 방어)
        var dup = paymentRepository.findByIdempotencyKey(idemKey);
        if (dup.isPresent()) {
            return new Started(dup.get().getId(), order.getAmount(),
                    PaymentResponse.of(dup.get().getId(), dup.get().getStatus().name(), order.getStatus().name()));
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (order.getExpiresAt().isBefore(LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.PAYMENT_TIMEOUT);
        }
        if (!"vbank".equals(method) && !IMMEDIATE.contains(method)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Payment payment = paymentRepository.save(Payment.builder()
                .orderId(orderId).method(method).provider(provider)
                .amount(order.getAmount()).idempotencyKey(idemKey).build());
        return new Started(payment.getId(), order.getAmount(), null);
    }

    /** TX2(가상계좌): 발급받은 계좌를 결제 행에 확정하고 주문을 입금 대기로 옮긴다. */
    private PaymentResponse assignVbankTx(Long paymentId, Long orderId, PaymentGateway.VbankIssue vb) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        payment.assignVbank(vb.account(), order.getExpiresAt(), vb.secret()); // 입금기한 = 결제 제한시각
        // 벌크 UPDATE(clearAutomatically)가 컨텍스트를 비우기 전에 계좌/기한/secret을 확정(TS-007)
        paymentRepository.saveAndFlush(payment);
        orderRepository.markVbankWaiting(orderId); // PENDING→VBANK_WAITING
        return PaymentResponse.vbank(payment.getId(), OrderStatus.VBANK_WAITING.name(),
                vb.account(), order.getExpiresAt());
    }

    /**
     * 승인 결과를 반영한다. 트랜잭션 밖에서 불린다.
     *
     * 확정이 실패하면(만료 sweep이 좌석을 먼저 풀었다) PG 승인만 남으므로 취소로 되돌린다. 그 취소도
     * 외부 호출이라 트랜잭션 밖인 여기서 한다. 예전에는 롤백 직전 트랜잭션 안에서 불렀다(TS-011).
     */
    private PaymentResponse settle(Long paymentId, Long orderId, int amount,
                                   ApproveResult res, OrderStatus from) {
        if (!res.success()) {
            // FAILED만 기록, 주문 PENDING 유지(재시도 가능)
            return tx.execute(status -> failPaymentTx(paymentId, orderId));
        }
        try {
            return tx.execute(status -> finalizePaidTx(paymentId, orderId, from, res.pgTid()));
        } catch (RuntimeException e) {
            compensateApproval(orderId, amount, res.pgTid());
            // 승인만 취소하고 결제 행은 실패로 남긴다. READY로 두면 입금 대기와 구분되지 않는다.
            tx.execute(status -> failPaymentTx(paymentId, orderId));
            throw e;
        }
    }

    /**
     * 확정하지 못한 승인을 되돌린다(TS-011 3)).
     *
     * 취소에 실패해도 원 예외를 그대로 올린다. 승인 직후 크래시 구간과 마찬가지로
     * PaymentReconciliationService가 다음 틱에 정리한다(ADR-011).
     */
    private void compensateApproval(Long orderId, int amount, String pgTid) {
        try {
            // 멱등키는 승인 단위로 고정한다(pgTid). 같은 승인을 두 번 취소하지 않는다.
            gateway.refund(pgTid, amount, "void-" + pgTid);
        } catch (RuntimeException ex) {
            log.warn("[payment] 보상 취소 실패 orderId={} pgTid={}: {}", orderId, pgTid, ex.getMessage());
        }
    }

    private PaymentResponse failPaymentTx(Long paymentId, Long orderId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (payment.getStatus() == PaymentStatus.READY) {
            payment.fail();
            paymentRepository.save(payment);
        }
        return PaymentResponse.of(payment.getId(), payment.getStatus().name(), currentStatus(orderId).name());
    }

    /** TX2: 승인 확정. 엔티티는 트랜잭션 경계를 넘으면 detached라 여기서 다시 읽는다. */
    private PaymentResponse finalizePaidTx(Long paymentId, Long orderId, OrderStatus from, String pgTid) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        finalizePaid(order, payment, from, pgTid);
        return PaymentResponse.of(payment.getId(), PaymentStatus.APPROVED.name(), currentStatus(orderId).name());
    }

    /** 무통장 입금 확인(개발/데모 트리거 · 실제는 PG 웹훅). VBANK_WAITING→PAID로 확정. */
    @Transactional
    public PaymentResponse confirmVbankDeposit(Long userId, Long orderId) {
        Order order = ownedOrder(orderId, userId);
        if (order.getStatus() != OrderStatus.VBANK_WAITING) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        Payment payment = paymentRepository
                .findFirstByOrderIdAndStatusOrderByIdDesc(orderId, PaymentStatus.READY)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        finalizePaidWithVoid(order, payment, OrderStatus.VBANK_WAITING, "DEV-DEPOSIT");
        // 발송은 finalizePaid 뒤로 둔다. 레지스트리가 커밋 후로 미뤄 주지만(AfterCommit),
        // 확정 전에 알림을 적어 두면 읽는 사람이 순서를 오해한다.
        // finalizePaid는 만료 sweep에 지면 롤백되는 경로가 있다(TS-011).
        orderSse.broadcast(orderId, "payment.vbank.deposited", Map.of("orderId", orderId));
        return PaymentResponse.of(payment.getId(), PaymentStatus.APPROVED.name(), currentStatus(orderId).name());
    }

    /**
     * 가상계좌 입금 웹훅(Toss DEPOSIT_CALLBACK) 처리. 위조·재전송을 방어한다.
     * - 검증: 발급 때 저장한 vbank_secret과 웹훅 secret 대조(불일치 → FORBIDDEN, HMAC 서명은 지급대행 전용).
     * - 멱등: Toss는 2xx 못 받으면 최대 7회 재전송하므로, 이미 PAID면 그대로 성공 응답(no-op).
     * - status가 완료(DONE)일 때만 VBANK_WAITING→PAID 확정.
     * tossOrderId는 결제창 규약 "FLOWTICKET-ORDER-{id}".
     */
    @Transactional
    public void handleVbankDepositWebhook(String tossOrderId, String status, String secret) {
        Long orderId = parseOrderId(tossOrderId);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        Payment payment = paymentRepository
                .findFirstByOrderIdAndStatusOrderByIdDesc(orderId, PaymentStatus.READY)
                .orElse(null);
        // 이미 확정됐거나(READY 없음=PAID) 재전송이면 멱등 no-op
        if (payment == null || order.getStatus() != OrderStatus.VBANK_WAITING) {
            return;
        }
        // 위조 검증: 저장 secret과 웹훅 secret 대조(발급분에 secret이 있을 때만 신뢰)
        if (payment.getVbankSecret() == null || !payment.getVbankSecret().equals(secret)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!"DONE".equals(status)) {
            return; // 입금 완료 상태가 아니면 대기 유지
        }
        finalizePaidWithVoid(order, payment, OrderStatus.VBANK_WAITING, "TOSS-DEPOSIT-" + payment.getId());
        // 발송은 finalizePaid 뒤로 둔다. 레지스트리가 커밋 후로 미뤄 주지만(AfterCommit),
        // 확정 전에 알림을 적어 두면 읽는 사람이 순서를 오해한다.
        // finalizePaid는 만료 sweep에 지면 롤백되는 경로가 있다(TS-011).
        orderSse.broadcast(orderId, "payment.vbank.deposited", Map.of("orderId", orderId));
    }

    private Long parseOrderId(String tossOrderId) {
        if (tossOrderId == null || !tossOrderId.startsWith("FLOWTICKET-ORDER-")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            return Long.parseLong(tossOrderId.substring("FLOWTICKET-ORDER-".length()));
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * 입금 확정 경로(가상계좌)의 확정 + 보상.
     *
     * 이 두 경로는 들어오는 방향이라 트랜잭션 안에 나가는 외부 호출이 없다. 확정 실패 시의 보상만
     * 예전 위치(트랜잭션 안)에 그대로 둔다. 승인 경로처럼 나누려면 웹훅 처리까지 손봐야 하는데
     * 여기서 얻는 것은 없다 — 취소 대상이 실제 PG 승인이 아니라 입금 식별자다.
     */
    private void finalizePaidWithVoid(Order order, Payment payment, OrderStatus from, String pgTid) {
        try {
            finalizePaid(order, payment, from, pgTid);
        } catch (RuntimeException e) {
            try {
                gateway.refund(pgTid, order.getAmount(), "void-" + pgTid);
            } catch (RuntimeException ex) {
                log.warn("[payment] 보상 취소 실패 orderId={} pgTid={}: {}",
                        order.getId(), pgTid, ex.getMessage());
            }
            throw e;
        }
    }

    /** 승인 확정: payment APPROVED(먼저 flush) → 주문 조건부 전이 → 좌석 SOLD·hold CONVERTED → order.paid. */
    private void finalizePaid(Order order, Payment payment, OrderStatus from, String pgTid) {
        payment.approve(pgTid);
        paymentRepository.saveAndFlush(payment); // 벌크 UPDATE의 컨텍스트 클리어 전에 확정(TS-007)

        int updated = orderRepository.markPaid(order.getId(), from);
        if (updated == 1) {
            List<Long> seatIds = orderItemRepository.findByOrderId(order.getId()).stream()
                    .map(OrderItem::getSeatId).toList();
            int sold = seatRepository.sellSeats(seatIds, SeatStatus.SOLD, SeatStatus.HELD);
            int converted = holdRepository.convertHold(order.getHoldId());
            // 영향 행 수 검증(ADR-003). 만료 sweep이 먼저 이겨 좌석/홀드가 이미 풀렸으면(HELD 아님) 0행 →
            // 예외로 트랜잭션 전체 롤백(markPaid·approve 포함). "주문 PAID인데 좌석은 AVAILABLE"
            // = 결제했는데 좌석이 재판매되는 반대 방향 레이스 차단(TS-011).
            if (sold != seatIds.size() || converted != 1) {
                // 확정 불가(TS-011). 예외로 이 트랜잭션 전체를 롤백한다(markPaid·approve 포함).
                // PG 승인을 되돌리는 보상은 여기서 하지 않는다 — 그것도 외부 호출이라 트랜잭션
                // 밖에서 해야 하고, 경로마다 주체가 다르다(승인 경로는 settle, 입금 경로는 래퍼).
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
            }
            // 아웃박스 적재(ADR-010): 이 트랜잭션과 같은 커밋에 이벤트를 남긴다.
            // 롤백되면 행도 사라져 유령 이벤트 0, 커밋되면 반드시 남아 릴레이가 재시도로 발행한다(유실 0).
            // (구 AFTER_COMMIT 발행은 커밋 후 크래시/브로커 다운 시 이벤트가 영구 유실됐다.)
            appendOutbox("order.paid", order.getId());
        }
    }

    /**
     * 아웃박스 행 적재. id를 먼저 만들어 payload의 eventId와 같은 UUID를 쓴다.
     * 행 PK가 곧 소비자 멱등 키라 릴레이가 재발행해도 소비는 한 번만 일어난다(ADR-010).
     */
    private void appendOutbox(String type, Long orderId) {
        UUID eventId = UUID.randomUUID();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new OrderEvent(type, orderId, eventId));
        } catch (JsonProcessingException e) {
            // 단순 레코드라 현실적으로 발생하지 않음. 발생 시 결제 트랜잭션과 함께 롤백되는 편이 안전.
            throw new IllegalStateException("아웃박스 payload 직렬화 실패: " + type, e);
        }
        outboxRepository.save(new OutboxEvent(eventId, AGGREGATE_ORDER, orderId, type, payload));
    }

    private Order ownedOrder(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return order;
    }

    private OrderStatus currentStatus(Long orderId) {
        return orderRepository.findById(orderId).map(Order::getStatus).orElse(OrderStatus.PAID);
    }
}
