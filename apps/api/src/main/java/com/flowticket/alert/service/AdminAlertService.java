package com.flowticket.alert.service;

import com.flowticket.alert.domain.AlertSettings;
import com.flowticket.alert.dto.AlertSettingsResponse;
import com.flowticket.alert.repository.AlertSettingsRepository;
import com.flowticket.dlq.domain.DlqStatus;
import com.flowticket.dlq.repository.DlqMessageRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영 알림 임계치 조회·수정(S07). 현재 DLQ 적체와 비교해 breached 여부를 계산. */
@Service
public class AdminAlertService {

    private final AlertSettingsRepository alertRepository;
    private final DlqMessageRepository dlqRepository;

    public AdminAlertService(AlertSettingsRepository alertRepository, DlqMessageRepository dlqRepository) {
        this.alertRepository = alertRepository;
        this.dlqRepository = dlqRepository;
    }

    @Transactional(readOnly = true)
    public AlertSettingsResponse get() {
        return toResponse(settings());
    }

    @Transactional
    public AlertSettingsResponse update(int dlqPendingThreshold) {
        AlertSettings settings = settings();
        settings.updateThreshold(dlqPendingThreshold);
        return toResponse(settings);
    }

    private AlertSettings settings() {
        return alertRepository.findById(AlertSettings.SINGLETON_ID)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)); // V13에서 시드됨
    }

    private AlertSettingsResponse toResponse(AlertSettings settings) {
        long dlqPending = dlqRepository.countByStatus(DlqStatus.PENDING);
        return new AlertSettingsResponse(
                settings.getDlqPendingThreshold(),
                dlqPending,
                dlqPending >= settings.getDlqPendingThreshold());
    }
}
