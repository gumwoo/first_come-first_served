package com.flowticket.event.dto;

/**
 * KOPIS 상세 동기화 진행 상황.
 *
 * <p>상세는 회차당 {@code kopis.sync.detail-batch-limit}건씩만 처리되므로, 갓 만든 클러스터에서는
 * 여러 회차가 필요하다. 밖에서 진행을 판단할 수단이 없어(공개 API도 관리자 API도
 * {@code detailSyncedAt}을 내려주지 않았다) 기동 스크립트가 개별 필드로 대신 세고 있었는데,
 * <b>그 대리값이 틀렸다</b> — {@code runningTime}은 상세를 받아도 비어 있을 수 있다.
 *
 * @param totalEvents   KOPIS에서 온 공연 수(kopisId가 있는 것)
 * @param detailMissing 그중 상세를 한 번도 못 받은 수. 0이면 초기 수집 완료.
 */
public record KopisSyncStatusResponse(long totalEvents, long detailMissing) {}
