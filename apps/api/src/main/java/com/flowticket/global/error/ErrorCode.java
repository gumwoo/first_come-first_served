package com.flowticket.global.error;

import org.springframework.http.HttpStatus;

/**
 * 에러코드 단일 정의. contracts/error-codes.yaml 과 일치해야 한다(하네스 검사).
 */
public enum ErrorCode {
    // 인증/인가
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
    // 검증
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT),
    // 선착순 핵심
    SOLD_OUT(HttpStatus.CONFLICT),
    QUEUE_EXPIRED(HttpStatus.GONE),
    HOLD_EXPIRED(HttpStatus.GONE),
    QUEUE_NOT_ADMITTED(HttpStatus.FORBIDDEN),
    MAX_PER_USER_EXCEEDED(HttpStatus.CONFLICT),
    DUPLICATE_BOOKING(HttpStatus.CONFLICT),
    // 결제
    PAYMENT_FAILED(HttpStatus.PAYMENT_REQUIRED),
    PAYMENT_TIMEOUT(HttpStatus.REQUEST_TIMEOUT),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    // 환불
    REFUND_NOT_ALLOWED(HttpStatus.CONFLICT),
    // 공통
    NOT_FOUND(HttpStatus.NOT_FOUND),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
