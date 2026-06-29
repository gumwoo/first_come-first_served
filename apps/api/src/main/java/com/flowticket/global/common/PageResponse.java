package com.flowticket.global.common;

import java.util.List;
import org.springframework.data.domain.Page;

/** 목록 응답 공통 형식: { items, page, size, total } (api-rules.md §3). */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
