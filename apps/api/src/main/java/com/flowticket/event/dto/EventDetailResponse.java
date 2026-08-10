package com.flowticket.event.dto;

import com.flowticket.event.domain.Event;
import java.time.LocalDate;

/**
 * 공연 상세. <b>전부 DB에서 온다 — 이 응답을 만드는 데 외부 호출이 없다.</b>
 *
 * <p>예전에는 KOPIS 상세를 요청마다 동기 호출해 병합했다. 그러면 외부 호출량이 우리 트래픽의
 * 함수가 되어 KOPIS 이용 제한(IP당 1초 10회)을 부하 시 약 7배 초과했고, 사용자 응답시간도 외부
 * 지연에 직접 종속됐다. 이제 동기화 배치가 미리 채운 값을 읽기만 한다.
 *
 * <p>아직 상세를 못 받은 공연은 해당 필드가 {@code null}이다 — 예전에 외부 호출이 실패했을 때
 * 나가던 축약 응답과 같은 모양이라 클라이언트 계약은 바뀌지 않는다.
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
