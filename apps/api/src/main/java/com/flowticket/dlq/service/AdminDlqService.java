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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 운영 DLQ 관리(S07 Phase 4c). 적재 조회 + 재시도(원본 토픽 재발행)·폐기. */
@Service
public class AdminDlqService {

    private final DlqMessageRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public AdminDlqService(DlqMessageRepository repository, KafkaTemplate<String, Object> kafkaTemplate,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<DlqMessageSummary> list(String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        var result = StringUtils.hasText(status)
                ? repository.findByStatusOrderByIdDesc(parseStatus(status), pageable)
                : repository.findAllByOrderByIdDesc(pageable);
        return PageResponse.from(result.map(DlqMessageSummary::from));
    }

    /** 원본 토픽으로 재발행 후 RETRIED 표시. 재발행이 또 실패하면 다시 DLT로 적재됨(정상). */
    @Transactional
    public void retry(Long id) {
        DlqMessage message = find(id);
        try {
            OrderEvent event = objectMapper.readValue(message.getPayload(), OrderEvent.class);
            kafkaTemplate.send(message.getTopic(), String.valueOf(event.orderId()), event);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, e);
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
