package com.flowticket.event.domain;

/** 공연 상태. contracts/enums.yaml EventStatus 와 일치. */
public enum EventStatus {
    DRAFT,
    SCHEDULED,
    ON_SALE,
    PAUSED,
    SOLD_OUT,
    CLOSED;

    /**
     * 예매(대기열 진입·좌석 선점)를 허용하는 상태인가.
     *
     * <p><b>ON_SALE 하나뿐이다.</b> 나머지는 각각 이유가 있다.
     * <pre>
     *   DRAFT      아직 공개하지 않은 공연
     *   SCHEDULED  공연예정 — 판매 시작 전(KOPIS "공연예정" 매핑, docs/rules/domain/event.md)
     *   PAUSED     운영자가 판매를 중지
     *   SOLD_OUT   매진
     *   CLOSED     종료
     * </pre>
     *
     * <p>⚠️ {@code SeatSeeder}의 {@code SELLABLE}(ON_SALE + SCHEDULED)과 다르다. 그쪽은
     * <b>좌석을 미리 만들어 두는</b> 대상이고, 이쪽은 <b>지금 팔아도 되는가</b>다.
     * SCHEDULED 공연의 좌석을 선시딩해 두는 것과 그 공연을 지금 예매하게 하는 것은 별개다.
     *
     * <p>프론트 상세 화면도 SCHEDULED를 {@code beforeOpen}으로 막고 있어 의미가 일치한다.
     */
    public boolean isBookable() {
        return this == ON_SALE;
    }
}
