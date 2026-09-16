package com.flowticket.global.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 목록 조회 공통 페이징 입력.
 *
 * <p>왜 값 객체인가: 검증 없이 {@code ?page=-1}이 들어가면 {@code PageRequest.of()}가
 * {@link IllegalArgumentException}을 던져 클라이언트 입력 오류가 500으로 나간다.
 * 경계에서 한 번 검증해 모든 목록 API가 같은 규칙을 쓴다.
 *
 * <p>왜 {@code IllegalArgumentException}을 400으로 잡지 않았나: 그 예외는 서버 내부
 * 프로그래밍 오류에서도 흔히 쓰인다. 전부 400으로 바꾸면 진짜 서버 버그가 클라이언트 오류로
 * 숨는다. 잘못된 입력은 경계에서 막고, 검증 계열 예외만 명시적으로 400으로 처리한다.
 *
 * <p>왜 {@code Integer}인가: 생성자 바인딩은 값이 없으면 null을 넘긴다. {@code int}면
 * 언박싱에서 터지므로, 여기서 받아 기본값을 채운다. 쿼리스트링 형태({@code ?page=0&size=20})는
 * 그대로라 호출하는 쪽은 바뀌지 않는다.
 *
 * <p>{@code size} 상한이 필요한 이유: 상한이 없으면 대량 행과 TEXT payload를 한 요청에서
 * 조회·직렬화할 수 있어 과도한 메모리 사용과 응답 지연 위험이 있다(운영 DLQ·아웃박스 목록).
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
