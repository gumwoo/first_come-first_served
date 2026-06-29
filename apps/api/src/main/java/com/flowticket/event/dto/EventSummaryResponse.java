package com.flowticket.event.dto;

import com.flowticket.event.domain.Event;
import java.time.LocalDate;

/** 목록/카드용 요약. */
public record EventSummaryResponse(
        Long id,
        String title,
        String venue,
        String region,
        String genre,
        String posterUrl,
        LocalDate startDate,
        String status,
        Integer basePrice
) {
    public static EventSummaryResponse from(Event e) {
        return new EventSummaryResponse(
                e.getId(), e.getTitle(), e.getVenue(), e.getRegion(), e.getGenre(), e.getPosterUrl(),
                e.getStartDate(), e.getStatus().name(), e.getBasePrice());
    }
}
