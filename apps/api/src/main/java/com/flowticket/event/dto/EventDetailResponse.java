package com.flowticket.event.dto;

import com.flowticket.event.domain.Event;
import com.flowticket.event.kopis.KopisEventDetail;
import java.time.LocalDate;

/** 공연 상세. DB 메타 + (lazy) KOPIS 상세(관람시간/연령/가격/출연진/줄거리/일정). */
public record EventDetailResponse(
        Long id,
        String title,
        String venue,
        String region,
        String genre,
        String posterUrl,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        Integer basePrice,
        // KOPIS 상세(lazy)
        String runningTime,
        String ageLimit,
        String priceText,
        String cast,
        String synopsis,
        String schedule
) {
    /** DB 기본만(상세 호출 실패/없음). */
    public static EventDetailResponse from(Event e) {
        return new EventDetailResponse(
                e.getId(), e.getTitle(), e.getVenue(), e.getRegion(), e.getGenre(), e.getPosterUrl(),
                e.getStartDate(), e.getEndDate(), e.getStatus().name(), e.getBasePrice(),
                e.getRunningTime(), e.getAgeLimit(), null, null, null, null);
    }

    /** DB + KOPIS 상세 병합. */
    public static EventDetailResponse from(Event e, KopisEventDetail d) {
        return new EventDetailResponse(
                e.getId(), e.getTitle(), e.getVenue(), e.getRegion(), e.getGenre(), e.getPosterUrl(),
                e.getStartDate(), e.getEndDate(), e.getStatus().name(), e.getBasePrice(),
                d.runningTime, d.ageLimit, d.priceText, d.cast, d.synopsis, d.schedule);
    }
}
