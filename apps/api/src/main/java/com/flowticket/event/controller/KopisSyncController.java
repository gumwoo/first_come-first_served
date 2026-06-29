package com.flowticket.event.controller;

import com.flowticket.event.kopis.KopisSyncService;
import com.flowticket.global.common.ApiResponse;
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

    @PostMapping("/admin/sync/kopis")
    public ApiResponse<Map<String, Integer>> sync() {
        int synced = kopisSyncService.sync();
        return ApiResponse.ok(Map.of("synced", synced));
    }
}
