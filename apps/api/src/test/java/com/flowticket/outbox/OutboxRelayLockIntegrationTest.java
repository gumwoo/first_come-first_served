package com.flowticket.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.order.event.OrderEvent;
import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import com.flowticket.outbox.service.OutboxPublisher;
import com.flowticket.outbox.service.OutboxRelay;
import com.flowticket.support.IntegrationTestSupport;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 릴레이의 락(ShedLock)과 트랜잭션의 관계.
 *
 * 1) 락은 트랜잭션 바깥에서 잡고 푼다(SchedulerLockConfig Javadoc의 주장). 반대로 트랜잭션 안에서 풀리면, 마킹이 커밋되기 전에
 *    다른 파드가 락을 잡아 아직 PENDING으로 보이는 행을 다시 집는다(중복 발행 — kafka-removal-design §6 (나)).
 * 2) 락 저장소가 실패하면 그 틱은 발행하지 않는다 — 재시도 경로(PENDING·attempts 증가)와는 다른 사례다.
 *    "건너뛴다"는 ShedLock 동작에서 끌어낸 추론이라 여기서 확인한다(예외가 그대로 올라와도 발행 0이면 같다).
 */
@SpringBootTest
@Import(OutboxRelayLockIntegrationTest.RecordingLockConfig.class)
class OutboxRelayLockIntegrationTest extends IntegrationTestSupport {

    /** 릴레이 락의 획득·해제 시점에 트랜잭션이 열려 있었는지 기록한다. 다른 락은 그냥 준다. */
    static class RecordingLockProvider implements LockProvider {
        volatile Boolean txActiveAtLock;
        volatile Boolean txActiveAtUnlock;
        volatile boolean failing;

        @Override
        public Optional<SimpleLock> lock(LockConfiguration config) {
            if (!"outbox-relay".equals(config.getName())) {
                return Optional.of(() -> { });
            }
            if (failing) {
                throw new IllegalStateException("lock store down");
            }
            txActiveAtLock = TransactionSynchronizationManager.isActualTransactionActive();
            return Optional.of(() -> txActiveAtUnlock = TransactionSynchronizationManager.isActualTransactionActive());
        }
    }

    @TestConfiguration
    static class RecordingLockConfig {
        @Bean
        @Primary
        RecordingLockProvider recordingLockProvider() {
            return new RecordingLockProvider();
        }
    }

    @MockBean OutboxPublisher publisher;
    @Autowired RecordingLockProvider locks;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired OutboxRelay relay;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void reset() {
        locks.txActiveAtLock = null;
        locks.txActiveAtUnlock = null;
        locks.failing = false;
    }

    @Test
    void 락은_트랜잭션_바깥에서_잡고_푼다() throws Exception {
        append(1L);
        when(publisher.publish(any())).thenReturn(1L);

        relay.publishPending();

        assertThat(locks.txActiveAtLock).as("락 획득 시 트랜잭션").isFalse();
        assertThat(locks.txActiveAtUnlock).as("락 해제 시 트랜잭션(열려 있으면 커밋 전에 락이 풀린다)").isFalse();
    }

    @Test
    void 락_저장소가_실패하면_그_틱은_발행하지_않는다() throws Exception {
        UUID id = append(2L);
        locks.failing = true;

        try {
            relay.publishPending();
        } catch (RuntimeException ignored) {
            // 예외가 올라오든 삼켜지든, 판정은 "발행 0 · 행 불변"이다
        }

        verify(publisher, never()).publish(any());
        OutboxEvent row = outboxRepository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
    }

    private UUID append(long orderId) throws Exception {
        UUID eventId = UUID.randomUUID();
        String payload = mapper.writeValueAsString(new OrderEvent("order.paid", orderId, eventId));
        outboxRepository.save(new OutboxEvent(eventId, "order", orderId, "order.paid", payload));
        return eventId;
    }
}
