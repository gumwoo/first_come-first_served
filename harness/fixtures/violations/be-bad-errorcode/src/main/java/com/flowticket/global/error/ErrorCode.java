package com.flowticket.global.error;

import org.springframework.http.HttpStatus;

// VIOLATION: 계약(contracts/error-codes.yaml)에 없는 코드 GHOST_CODE 사용
// → 하네스 error-code 일치 검사가 실패해야 함
public enum ErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    GHOST_CODE(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
