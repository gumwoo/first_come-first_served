package com.flowticket.event.dto;

import com.flowticket.event.domain.Event;
import java.time.LocalDate;

/** 공연 상세. */
public record EventDetailResponse(
        Long id,
        String title,
        String venue,
        String genre,
        String posterUrl,
        LocalDate startDate,
        LocalDate endDate,
        String runningTime,
        String ageLimit,
        String status,
        Integer basePrice
) {
    public static EventDetailResponse from(Event e) {
        return new EventDetailResponse(
                e.getId(), e.getTitle(), e.getVenue(), e.getGenre(), e.getPosterUrl(),
                e.getStartDate(), e.getEndDate(), e.getRunningTime(), e.getAgeLimit(),
                e.getStatus().name(), e.getBasePrice());
    }
}
