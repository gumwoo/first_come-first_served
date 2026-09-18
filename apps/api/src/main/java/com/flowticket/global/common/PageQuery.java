package com.flowticket.global.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 목록 조회 공통 페이징 입력.
 *
 * 검증 없이 ?page=-1이 들어가면 PageRequest.of()의 IllegalArgumentException이 500으로 나간다.
 * 그 예외를 전역에서 400으로 바꾸지 않고, 경계인 이 값 객체에서 검증한다(서버 내부 버그까지 400으로
 * 숨기지 않기 위해).
 *
 * 필드가 Integer인 것은 생성자 바인딩이 값이 없으면 null을 넘기기 때문이다.
 * size 상한은 대량 행·TEXT payload를 한 요청에서 직렬화하지 않게 막는다(운영 DLQ·아웃박스 목록).
 */
public record PageQuery(
        @Min(value = 0, message = "0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "1 이상이어야 합니다.")
        @Max(value = MAX_SIZE, message = MAX_SIZE + " 이하여야 합니다.") Integer size) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PageQuery {
        if (page == null) {
            page = DEFAULT_PAGE;
        }
        if (size == null) {
            size = DEFAULT_SIZE;
        }
    }
}
