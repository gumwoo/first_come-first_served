package com.flowticket.admin.dto;

import com.flowticket.event.domain.Event;
import java.time.LocalDate;

/** 운영 이벤트 목록 항목(S07). */
public record AdminEventSummary(
        Long id,
        String title,
        String venue,
        String genre,
        String status,
        LocalDate startDate,
        LocalDate endDate,
        Integer basePrice,
        boolean fromKopis) {

    public static AdminEventSummary from(Event e) {
        return new AdminEventSummary(
                e.getId(), e.getTitle(), e.getVenue(), e.getGenre(), e.getStatus().name(),
                e.getStartDate(), e.getEndDate(), e.getBasePrice(), e.getKopisId() != null);
    }
}
