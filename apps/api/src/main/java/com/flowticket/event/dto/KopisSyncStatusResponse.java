package com.flowticket.event.dto;

/**
 * KOPIS 상세 동기화 진행 상황.
 *
 * 상세는 회차당 kopis.sync.detail-batch-limit건씩만 처리되므로, 갓 만든 클러스터에서는
 * 여러 회차가 필요하다. 기동 스크립트가 진행을 판단할 수 있게 detailSyncedAt 기준으로 센다.
 * runningTime 같은 개별 필드는 상세를 받아도 비어 있을 수 있어 대리값이 되지 못한다.
 *
 * @param totalEvents   KOPIS에서 온 공연 수(kopisId가 있는 것)
 * @param detailMissing 그중 상세를 한 번도 못 받은 수. 0이면 초기 수집 완료.
 */
public record KopisSyncStatusResponse(long totalEvents, long detailMissing) {}
