package com.flowticket.event.controller;

import com.flowticket.event.kopis.KopisSyncService;
import com.flowticket.global.common.ApiResponse;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import java.util.Map;
import com.flowticket.event.dto.KopisSyncStatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
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

    /**
     * 상세 동기화 진행 상황. 기동 스크립트가 "초기 수집이 끝났는가"를 판정하는 데 쓴다.
     *
     * <p>읽기 전용이라는 점이 중요하다 — 이전 스크립트는 진행 여부를 확인하려고
     * {@code POST /admin/sync/kopis}를 다시 호출했는데, <b>409가 아니면 그 호출이 새 동기화를
     * 시작해버린다.</b> 관측이 상태를 바꾸면 안 된다.
     */
    @GetMapping("/admin/sync/kopis/status")
    public ApiResponse<KopisSyncStatusResponse> status() {
        return ApiResponse.ok(kopisSyncService.status());
    }
}
