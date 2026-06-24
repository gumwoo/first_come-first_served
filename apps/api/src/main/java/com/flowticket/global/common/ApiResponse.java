package com.flowticket.global.common;

/**
 * 공통 응답 래퍼. 성공은 { "data": ... }.
 * 실패({ "error": ... })는 global.error.GlobalExceptionHandler가 생성한다.
 * (api-rules.md §3 / backend-rules.md §4)
 */
public record ApiResponse<T>(T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data);
    }
}
