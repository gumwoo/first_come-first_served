package com.flowticket.outbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.global.config.KafkaConfig;
import com.flowticket.order.event.OrderEvent;
import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아웃박스 릴레이(S08, ADR-010). PENDING 행을 오래된 순으로 Kafka에 발행하고, <b>발행 성공을 확인한 뒤</b>
 * PUBLISHED로 마킹한다(publish-then-mark). 크래시로 마킹 전에 죽으면 다음 틱에 재발행되므로
 * <b>at-least-once</b>이고 유실이 0이다 — 중복은 소비자 멱등(eventId)이 흡수한다.
 *
 * <p>CDC(Debezium) 대신 <b>폴링</b>을 쓴다: 정합성 보장은 동일하고 새 인프라가 0이라 이 규모에 맞는다.
 * 멀티 Pod에서는 ShedLock으로 한 인스턴스만 돌아 발행 순서·중복을 통제한다.
 */
@Slf4j
@Service
public class OutboxRelay {

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper mapper;
    private final int batchSize;
    private final long sendTimeoutMs;
    private final int retentionDays;

    public OutboxRelay(OutboxEventRepository repository, KafkaTemplate<String, Object> kafkaTemplate,
                       ObjectMapper mapper,
                       @Value("${outbox.batch-size:100}") int batchSize,
                       @Value("${outbox.send-timeout-ms:3000}") long sendTimeoutMs,
                       @Value("${outbox.retention-days:7}") int retentionDays) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
        this.retentionDays = retentionDays;
    }

    /**
     * 미발행분을 배치로 발행. <b>실패를 두 종류로 나눈다.</b>
     *
     * <p><b>일시적 실패</b>(브로커 다운·타임아웃·네트워크) — PENDING을 유지하고 <b>남은 배치를
     * 중단</b>한다. (1) 배치 전체가 타임아웃으로 트랜잭션을 오래 붙잡는 걸 막고, (2) 같은 주문의
     * 후속 이벤트가 앞 이벤트를 추월하지 않게 한다. 다음 틱에 재시도하면 복구된다.
     *
     * <p><b>결정적 실패</b>(payload 역직렬화 불가) — 다음 틱에도 결과가 같다. 중단하면 그 행이
     * 정렬 선두를 영구히 차지해 <b>뒤따르는 모든 이벤트가 멈춘다</b>. 그래서 중단하지 않고
     * 그 행만 DEAD로 격리한 뒤 진행한다.
     *
     * <p><b>격리해도 순서는 지킨다.</b> DEAD가 난 aggregate의 후속 이벤트는 보류한다 — 발행된
     * 것들끼리의 상대 순서가 유지돼도, 앞 이벤트가 영영 안 나간 채 뒤 이벤트만 나가면 소비자는
     * 인과를 거꾸로 본다. 차단 범위를 <b>해당 aggregate로 한정</b>하는 것이 이 수정의 요점이다.
     *
     * <p>시도 횟수만으로 DEAD로 넘기지 않는다. 브로커가 오래 죽어 있었다는 이유로 멀쩡한 이벤트를
     * 버리면 아웃박스의 존재 이유가 사라진다.
     */
    // initialDelay = 주기: 부팅 직후 첫 틱을 피해 기동을 가볍게 하고,
    // 주기를 크게 준 테스트에서는 스케줄 틱이 아예 끼어들지 않아 릴레이 직접 호출이 결정적이 된다.
    @Scheduled(fixedRateString = "${outbox.relay-interval-ms:1000}",
               initialDelayString = "${outbox.relay-interval-ms:1000}")
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT1M", lockAtLeastFor = "PT0S")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.findByStatusOrderByCreatedAtAsc(
                OutboxStatus.PENDING, PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return;
        }
        // 이전 틱에 격리된 aggregate. 배치 안에서 새로 DEAD가 나면 여기 더해, 같은 틱에 이어지는
        // 후속 이벤트도 곧바로 보류된다.
        Set<String> blocked = new HashSet<>(repository.findBlockedAggregateKeys(OutboxStatus.DEAD));

        int published = 0;
        int dead = 0;
        int held = 0;
        for (OutboxEvent row : batch) {
            String key = aggregateKey(row);
            if (blocked.contains(key)) {
                held++;
                continue;
            }
            OrderEvent event = deserialize(row);
            if (event == null) { // 결정적 실패 — 중단하지 않고 이 aggregate만 막는다
                blocked.add(key);
                dead++;
                continue;
            }
            if (!send(row, event)) { // 일시적 실패 — 남은 배치를 중단하고 다음 틱에 재시도
                break;
            }
            published++;
        }
        log.info("[outbox] 발행 {}/{}건 (격리 {}건, 보류 {}건)", published, batch.size(), dead, held);
    }

    /** 역직렬화 실패 = 결정적 실패. 그 자리에서 DEAD로 격리하고 null을 돌려준다. */
    private OrderEvent deserialize(OutboxEvent row) {
        try {
            return mapper.readValue(row.getPayload(), OrderEvent.class);
        } catch (Exception e) {
            row.markDead(e.toString());
            log.error("[outbox] payload 해석 불가 — 격리(DEAD) id={} type={} aggregateId={}: {}",
                    row.getId(), row.getType(), row.getAggregateId(), e.toString());
            return null;
        }
    }

    /** 발행 성공 시 true. 실패는 일시적으로 보고 PENDING을 유지해 다음 틱에 다시 시도한다. */
    private boolean send(OutboxEvent row, OrderEvent event) {
        try {
            // 파티션 키 = aggregateId(orderId) → 같은 주문 이벤트의 순서 보장(기존 발행 방식 계승).
            kafkaTemplate.send(KafkaConfig.ORDER_EVENTS_TOPIC, String.valueOf(row.getAggregateId()), event)
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS); // 브로커 확인 후에만 마킹
            row.markPublished();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            row.markAttemptFailed("interrupted");
            return false;
        } catch (Exception e) {
            row.markAttemptFailed(e.toString());
            log.warn("[outbox] 발행 실패 id={} type={} attempts={}: {}",
                    row.getId(), row.getType(), row.getAttempts(), e.toString());
            return false;
        }
    }

    /** DEAD 차단 판정 키. aggregateId만으로는 다른 aggregateType과 충돌할 수 있다. */
    private static String aggregateKey(OutboxEvent row) {
        return row.getAggregateType() + ":" + row.getAggregateId();
    }

    /**
     * 보존기간이 지난 <b>발행 완료</b> 행만 정리. PENDING·실패 행은 절대 지우지 않는다(유실 방지) —
     * 발행 이력을 일정 기간 남겨 "언제·몇 번째 시도에 나갔는지" 운영 가시성을 준다.
     */
    @Scheduled(cron = "${outbox.purge-cron:0 0 3 * * *}")
    @SchedulerLock(name = "outbox-purge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    @Transactional
    public void purgePublished() {
        int deleted = repository.deletePublishedBefore(
                OutboxStatus.PUBLISHED, LocalDateTime.now().minusDays(retentionDays));
        if (deleted > 0) {
            log.info("[outbox] 발행 완료 {}일 경과 {}건 정리", retentionDays, deleted);
        }
    }
}
