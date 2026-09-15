package com.flowticket.event.dto;

import com.flowticket.event.domain.Event;
import java.time.LocalDate;

/**
 * 공연 상세. 전부 DB에서 온다 — 이 응답을 만드는 데 외부 호출이 없다.
 *
 * <p>동기화 배치가 미리 채운 값을 읽기만 한다. 아직 상세를 못 받은 공연은 해당 필드가
 * {@code null}이다.
 */
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
    public static EventDetailResponse from(Event e) {
        return new EventDetailResponse(
                e.getId(), e.getTitle(), e.getVenue(), e.getRegion(), e.getGenre(), e.getPosterUrl(),
                e.getStartDate(), e.getEndDate(), e.getStatus().name(), e.getBasePrice(),
                e.getRunningTime(), e.getAgeLimit(),
                e.getPriceText(), e.getCastInfo(), e.getSynopsis(), e.getScheduleText());
    }
}
