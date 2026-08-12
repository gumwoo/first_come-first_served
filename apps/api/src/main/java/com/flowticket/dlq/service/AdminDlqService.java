package com.flowticket.dlq.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.dlq.domain.DlqMessage;
import com.flowticket.dlq.domain.DlqStatus;
import com.flowticket.dlq.dto.DlqMessageSummary;
import com.flowticket.dlq.repository.DlqMessageRepository;
import com.flowticket.global.common.PageResponse;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.order.event.OrderEvent;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 운영 DLQ 관리(S07 Phase 4c). 적재 조회 + 재시도(원본 토픽 재발행)·폐기. */
@Slf4j
@Service
public class AdminDlqService {

    private final DlqMessageRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    /** 재발행 확인 대기 상한. 아웃박스와 같은 기본값을 쓰되 키는 분리한다(운영 튜닝 축이 다르다). */
    private final long sendTimeoutMs;

    public AdminDlqService(DlqMessageRepository repository, KafkaTemplate<String, Object> kafkaTemplate,
                           ObjectMapper objectMapper,
                           @Value("${dlq.send-timeout-ms:3000}") long sendTimeoutMs) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Transactional(readOnly = true)
    public PageResponse<DlqMessageSummary> list(String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        var result = StringUtils.hasText(status)
                ? repository.findByStatusOrderByIdDesc(parseStatus(status), pageable)
                : repository.findAllByOrderByIdDesc(pageable);
        return PageResponse.from(result.map(DlqMessageSummary::from));
    }

    /**
     * 원본 토픽으로 재발행 후 RETRIED 표시. 재발행된 메시지가 <b>소비</b>에서 또 실패하면 다시
     * DLT로 적재된다(정상 — 그건 이 경로가 아니라 컨슈머 에러 핸들러가 처리한다).
     *
     * <p>⚠️ <b>브로커 확인 전에 RETRIED로 바꾸지 않는다.</b> 예전에는 {@code send()}만 호출하고
     * 곧바로 마킹했는데, 전송은 비동기라 순서가 이렇게 됐다([[TS-026]]).
     *
     * <pre>
     * send() 호출(아직 안 나감) → markRetried() → 트랜잭션 COMMIT → ...그 뒤 브로커 실패 응답
     * </pre>
     *
     * 실패 콜백을 아무도 보지 않으므로 <b>DB는 "재처리했다"는데 실제로는 유실</b>된다.
     * 게다가 상태가 RETRIED로 바뀌면 운영자 목록에서도 사라져, 이 설계의 전제인
     * "실패 메시지를 버리지 않는다"가 마지막 한 걸음에서 깨졌다.
     * {@code OutboxRelay.publishOne()}은 처음부터 {@code .get()}으로 확인하고 있었다 —
     * 같은 문제에 대한 처리가 코드베이스 안에서 갈려 있었던 셈이다.
     *
     * <p>발행이 실패하면 예외로 트랜잭션이 롤백돼 상태가 그대로 남고, 운영자가 다시 시도할 수 있다.
     * 반대로 발행 성공 후 커밋이 실패하면 중복 발행이 되는데, 컨슈머가 {@code eventId} 멱등을
     * 갖고 있어(ADR-010) 흡수된다 — 아웃박스와 같은 at-least-once 교환이다.
     */
    @Transactional
    public void retry(Long id) {
        DlqMessage message = find(id);

        // 페이로드가 깨진 것과 발행이 실패한 것은 운영 판단이 다르다.
        // 전자는 몇 번을 눌러도 실패하므로 폐기 대상이고, 후자는 나중에 다시 누르면 된다.
        // 예전에는 둘 다 VALIDATION_ERROR(400)로 뭉뚱그려 그 구분이 안 됐다.
        OrderEvent event;
        try {
            event = objectMapper.readValue(message.getPayload(), OrderEvent.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, e);
        }

        try {
            kafkaTemplate.send(message.getTopic(), String.valueOf(event.orderId()), event)
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS); // 브로커 확인 후에만 마킹
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, e);
        } catch (Exception e) {
            log.warn("[dlq] 재발행 실패 id={} topic={}: {}", id, message.getTopic(), e.toString());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, e);
        }

        message.markRetried();
    }

    @Transactional
    public void discard(Long id) {
        find(id).markDiscarded();
    }

    private DlqMessage find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private DlqStatus parseStatus(String status) {
        try {
            return DlqStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
