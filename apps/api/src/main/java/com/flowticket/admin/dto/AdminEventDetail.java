package com.flowticket.admin.dto;

import com.flowticket.event.domain.Event;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 운영 이벤트 상세(S07). 편집 폼 프리필용 전체 필드. */
public record AdminEventDetail(
        Long id,
        String kopisId,
        String title,
        String venue,
        String region,
        String genre,
        String posterUrl,
        LocalDate startDate,
        LocalDate endDate,
        String runningTime,
        String ageLimit,
        String status,
        Integer basePrice,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static AdminEventDetail from(Event e) {
        return new AdminEventDetail(
                e.getId(), e.getKopisId(), e.getTitle(), e.getVenue(), e.getRegion(), e.getGenre(),
                e.getPosterUrl(), e.getStartDate(), e.getEndDate(), e.getRunningTime(), e.getAgeLimit(),
                e.getStatus().name(), e.getBasePrice(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
