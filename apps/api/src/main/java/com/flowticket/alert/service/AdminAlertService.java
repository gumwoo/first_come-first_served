package com.flowticket.alert.service;

import com.flowticket.alert.domain.AlertSettings;
import com.flowticket.alert.dto.AlertSettingsResponse;
import com.flowticket.alert.repository.AlertSettingsRepository;
import com.flowticket.dlq.domain.DlqStatus;
import com.flowticket.dlq.repository.DlqMessageRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영 알림 임계치 조회·수정. 현재 DLQ 적체·아웃박스 격리(DEAD) 수와 비교해 초과 여부를 계산. */
@Service
public class AdminAlertService {

    private final AlertSettingsRepository alertRepository;
    private final DlqMessageRepository dlqRepository;
    private final OutboxEventRepository outboxRepository;

    public AdminAlertService(AlertSettingsRepository alertRepository, DlqMessageRepository dlqRepository,
                             OutboxEventRepository outboxRepository) {
        this.alertRepository = alertRepository;
        this.dlqRepository = dlqRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional(readOnly = true)
    public AlertSettingsResponse get() {
        return toResponse(settings());
    }

    @Transactional
    public AlertSettingsResponse update(Integer dlqPendingThreshold, Integer outboxDeadThreshold) {
        AlertSettings settings = settings();
        settings.updateThresholds(dlqPendingThreshold, outboxDeadThreshold);
        return toResponse(settings);
    }

    private AlertSettings settings() {
        return alertRepository.findById(AlertSettings.SINGLETON_ID)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)); // V13에서 시드됨
    }

    private AlertSettingsResponse toResponse(AlertSettings settings) {
        long dlqPending = dlqRepository.countByStatus(DlqStatus.PENDING);
        long outboxDead = outboxRepository.countByStatus(OutboxStatus.DEAD);
        return new AlertSettingsResponse(
                settings.getDlqPendingThreshold(),
                dlqPending,
                dlqPending >= settings.getDlqPendingThreshold(),
                settings.getOutboxDeadThreshold(),
                outboxDead,
                outboxDead >= settings.getOutboxDeadThreshold());
    }
}
