package com.flowticket.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.order.event.OrderEvent;
import com.flowticket.order.sse.OrderSseRegistry;
import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import com.flowticket.outbox.service.OutboxPublisher;
import com.flowticket.outbox.service.OutboxRelay;
import com.flowticket.support.IntegrationTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * IMP-011 측정(ADR-010): 발행 대상 장애 중 결제된 이벤트가 유실되는가를 before/after로 센다.
 * ADR-028 뒤로 발행 대상은 Kafka가 아니라 Redis 채널(sse:order)이고, 도달은 각 파드의 구독자가 로컬 전달을
 * 시도한 것(OrderSseRegistry.deliverLocal)으로 센다.
 *
 * 장애는 발행기가 실패하도록 스텁해 재현한다(결정적). 두 경로를 같은 장애 창에 넣고, 복구 후 실제로
 * 구독자에 도달한 건수를 세어 유실을 측정한다.
 *   - before(naive, 커밋 후 직접 발행): 발행 실패를 삼킨다. 복구해도 재발행할 근거가 어디에도 없어 영구 유실.
 *   - after(outbox): 이벤트가 결제와 같은 커밋으로 DB에 남아 PENDING 유지 → 복구 후 릴레이가 재시도해 전량 발행.
 *
 * 장애 중 릴레이는 프록시 없이 직접 만들어 실패하는 발행기를 넣는다(빈 릴레이의 발행기를 바꾸면 컨텍스트가 갈린다).
 * 프록시가 없으면 @Transactional도 빠지므로 TransactionTemplate으로 감싸 실패 기록이 DB에 남게 한다.
 */
@SpringBootTest
class OutboxDeliveryIntegrationTest extends IntegrationTestSupport {

    private static final int TRIALS = 10;
    private static final long NAIVE_BASE = 5_000_000L;  // before 경로 orderId 구간
    private static final long OUTBOX_BASE = 6_000_000L; // after 경로 orderId 구간

    @SpyBean OrderSseRegistry orderSse;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired OutboxRelay relay; // 복구 후 정상 발행에 쓰는 실제 빈(실제 Redis로 보낸다)
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    private OutboxPublisher failingPublisher;
    private OutboxRelay relayDuringOutage;

    /** 구독자까지 실제로 도달한 orderId(= 유실되지 않은 이벤트). */
    private final Set<Long> delivered = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        delivered.clear();
        doAnswer(inv -> {
            delivered.add(inv.getArgument(0, Long.class));
            return null;
        }).when(orderSse).deliverLocal(any(), eq("order.paid"), any());

        failingPublisher = mock(OutboxPublisher.class);
        when(failingPublisher.publish(any())).thenThrow(new RedisConnectionFailureException("redis down"));
        // 실제 릴레이와 동일한 설정값(배치 100 / 틱 예산 45s / 보존 7일)
        relayDuringOutage = new OutboxRelay(outboxRepository, failingPublisher, mapper, 100, 45_000L, 7,
                Clock.systemDefaultZone(), new SimpleMeterRegistry());
    }

    /**
     * 틱 예산을 넘기면 남은 건을 다음 틱으로 넘긴다(ADR-022).
     *
     * 예산이 없으면 한 틱의 상한은 batch-size × send-timeout(100 × 3초 = 300초)이라 ShedLock
     * 임차(1분)를 넘긴다. 넘기는 순간 다른 파드가 같은 PENDING을 집어 릴레이가 겹쳐 돈다.
     * 예산을 0으로 두어 "첫 건을 집기 전에 예산이 끝난" 상황을 만든다.
     */
    @Test
    void 틱_예산을_넘기면_남은_건은_PENDING으로_남는다() {
        appendOutbox(9_100_001L);

        OutboxRelay noBudget = new OutboxRelay(outboxRepository, failingPublisher, mapper,
                100, 0L, 7, Clock.systemDefaultZone(), new SimpleMeterRegistry());
        inTransaction(noBudget::publishPending);

        assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING))
                .as("예산이 끝났으면 손대지 않고 다음 틱에 넘긴다").isEqualTo(1);
        verify(failingPublisher, never()).publish(any());
    }

    @Test
    void IMP011_발행_장애중_이벤트_유실_before_after() {
        // ── 장애 창(발행이 실패하는 구간) ─────────────────────────
        // before(naive): 실패를 삼키고 아무 기록도 남기지 않는다
        for (int i = 0; i < TRIALS; i++) {
            naivePublishSwallowing(OrderEvent.of("order.paid", NAIVE_BASE + i));
        }

        // after(outbox): 결제와 같은 커밋으로 남은 행을 릴레이가 발행 시도 → 실패해도 PENDING 유지
        for (int i = 0; i < TRIALS; i++) {
            appendOutbox(OUTBOX_BASE + i);
        }
        inTransaction(relayDuringOutage::publishPending);
        assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING))
                .as("장애 중에도 이벤트는 DB에 보존된다(유실 대신 재시도 대상)")
                .isEqualTo(TRIALS);

        // ── 복구 ──────────────────────────────────────────────────
        // naive는 재발행할 근거가 없어 아무 일도 일어나지 않는다. outbox만 릴레이가 재시도한다.
        relay.publishPending();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> deliveredIn(OUTBOX_BASE) == TRIALS);

        int lostBefore = TRIALS - deliveredIn(NAIVE_BASE);
        int lostAfter = TRIALS - deliveredIn(OUTBOX_BASE);

        // 벤치마크(benchmarks/outbox-delivery-*.json — Kafka 시기 기록)와 같은 수치를 테스트가 강제한다.
        assertThat(lostBefore).as("before: 장애 중 발행분 전량 영구 유실").isEqualTo(TRIALS);
        assertThat(lostAfter).as("after: 복구 후 전량 재발행 → 유실 0").isZero();
        assertThat(outboxRepository.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(TRIALS);
    }

    /** 커밋 후 직접 발행하며 실패를 삼키는 방식(복구 근거 없음 = 영구 유실). */
    private void naivePublishSwallowing(OrderEvent event) {
        try {
            failingPublisher.publish(event);
        } catch (Exception ignored) {
            // 결제는 이미 커밋됐고 DB가 진실원: 알림 유실은 감수(구 설계)
        }
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private void appendOutbox(long orderId) {
        UUID eventId = UUID.randomUUID();
        try {
            String payload = mapper.writeValueAsString(new OrderEvent("order.paid", orderId, eventId));
            outboxRepository.save(new OutboxEvent(eventId, "order", orderId, "order.paid", payload));
        } catch (Exception e) {
            throw new IllegalStateException("아웃박스 적재 실패", e);
        }
    }

    private int deliveredIn(long base) {
        return (int) delivered.stream()
                .filter(id -> id >= base && id < base + TRIALS)
                .count();
    }
}
