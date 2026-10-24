package com.flowticket.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;

/** 공통 예외 → 상태코드/바디 매핑 단위 검증. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 필수_파라미터_누락은_500이_아니라_400() {
        // TS-005 부수 발견: DELETE /queue/token 등 필수 @RequestParam 누락이 500으로 나가던 것 → 400.
        var ex = new MissingServletRequestParameterException("token", "String");

        ResponseEntity<ErrorResponse> res = handler.handleMissingParam(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().error().code()).isEqualTo(ErrorCode.VALIDATION_ERROR.name());
        assertThat(res.getBody().error().message()).contains("token");
    }

    @Test
    void Redis_연결_실패와_명령_시간_초과는_503() {
        for (Exception ex : new Exception[] {
                new RedisConnectionFailureException("down"), new QueryTimeoutException("Redis command timed out")}) {
            ResponseEntity<ErrorResponse> res = handler.handleUnavailable(ex);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(res.getBody()).isNotNull();
            assertThat(res.getBody().error().code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.name());
        }
    }
}
