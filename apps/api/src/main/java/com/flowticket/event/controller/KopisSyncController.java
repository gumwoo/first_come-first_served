package com.flowticket.event.controller;

import com.flowticket.event.kopis.KopisSyncService;
import com.flowticket.global.common.ApiResponse;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 운영자 KOPIS 수동 동기화 트리거. (S07 RBAC 전까지는 인증 사용자) */
@RestController
public class KopisSyncController {

    private final KopisSyncService kopisSyncService;

    public KopisSyncController(KopisSyncService kopisSyncService) {
        this.kopisSyncService = kopisSyncService;
    }

    /**
     * 수동 동기화. 스케줄 동기화와 같은 분산 락을 통과하므로, 이미 다른 실행이 진행 중이면
     * ShedLock이 호출을 건너뛰어 null이 돌아온다 → 조용히 "0건 처리"로 보이지 않도록 409로 알린다.
     */
    @PostMapping("/admin/sync/kopis")
    public ApiResponse<Map<String, Integer>> sync() {
        Integer synced = kopisSyncService.sync();
        if (synced == null) {
            throw new BusinessException(ErrorCode.SYNC_IN_PROGRESS);
        }
        return ApiResponse.ok(Map.of("synced", synced));
    }
}
