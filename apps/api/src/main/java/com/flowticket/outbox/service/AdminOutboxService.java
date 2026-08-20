package com.flowticket.outbox.service;

import com.flowticket.global.common.PageResponse;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.dto.OutboxEventSummary;
import com.flowticket.outbox.repository.OutboxEventRepository;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 운영 아웃박스 관리(S08). 격리된(DEAD) 행 조회 + 재발행·폐기.
 *
 * <p><b>왜 필요한가</b>: {@link OutboxRelay}가 결정적 실패를 DEAD로 격리하면서 "독성 행 하나가
 * 전체를 멈추는" 문제는 사라졌지만, 격리된 행은 purge되지 않고 계속 쌓인다. 창구가 없으면
 * <b>조용히 멈추는 문제를 조용히 쌓이는 문제로 옮긴 것</b>에 지나지 않는다.
 *
 * <p>판단은 사람이 한다(ADR-008). 자동 복구를 넣지 않는 이유는 결정적 실패의 정의 그대로다 —
 * 시스템이 스스로 풀 수 있었으면 애초에 DEAD가 아니다.
 */
@Slf4j
@Service
public class AdminOutboxService {

    private final OutboxEventRepository repository;

    public AdminOutboxService(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageResponse<OutboxEventSummary> list(String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        var result = StringUtils.hasText(status)
                ? repository.findByStatusOrderByCreatedAtDesc(parseStatus(status), pageable)
                : repository.findAllByOrderByCreatedAtDesc(pageable);
        return PageResponse.from(result.map(OutboxEventSummary::from));
    }

    /**
     * 다시 발행 대상으로 되돌린다(DEAD → PENDING). 발행은 릴레이가 한다 — 여기서 Kafka로 직접
     * 쏘지 않는다. <b>아웃박스의 발행 책임은 {@link OutboxRelay} 하나</b>여야 publish-then-mark
     * 순서와 aggregate 차단 판정이 한 곳에 남는다.
     */
    @Transactional
    public void requeue(UUID id) {
        OutboxEvent event = find(id);
        transition(event, OutboxEvent::requeue);
        log.info("[outbox-admin] 재발행 대기로 되돌림 id={} aggregateId={}", id, event.getAggregateId());
    }

    /** 발행을 포기한다(DEAD → DISCARDED). 같은 aggregate의 후속 이벤트가 다시 흐른다. */
    @Transactional
    public void discard(UUID id) {
        OutboxEvent event = find(id);
        transition(event, OutboxEvent::discard);
        log.warn("[outbox-admin] 발행 포기 id={} aggregateId={} type={}",
                id, event.getAggregateId(), event.getType());
    }

    private OutboxEvent find(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /**
     * 엔티티가 거부한 전이를 API 계약으로 옮긴다. 엔티티는 도메인 규칙을 모르는 호출자에게도
     * 같은 방식으로 실패해야 하므로 {@link IllegalStateException}을 던지고, 그것이 500으로
     * 새지 않게 여기서 409로 번역한다.
     */
    private void transition(OutboxEvent event, java.util.function.Consumer<OutboxEvent> action) {
        try {
            action.accept(event);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, e);
        }
    }

    private OutboxStatus parseStatus(String raw) {
        try {
            return OutboxStatus.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
