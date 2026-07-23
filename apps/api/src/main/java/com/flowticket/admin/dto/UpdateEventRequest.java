package com.flowticket.admin.dto;

import java.time.LocalDate;

/** 운영 이벤트 부분 수정(S07, PATCH). null 필드는 변경 없음. */
public record UpdateEventRequest(
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
        Integer basePrice) {}
