package com.flowticket.admin.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/** 운영 이벤트 수동 등록(S07). KOPIS 동기화가 아닌 직접 생성 → kopisId 없음. */
public record CreateEventRequest(
        @NotBlank String title,
        String venue,
        String region,
        String genre,
        String posterUrl,
        LocalDate startDate,
        LocalDate endDate,
        String runningTime,
        String ageLimit,
        String status,
        Integer basePrice) {}
