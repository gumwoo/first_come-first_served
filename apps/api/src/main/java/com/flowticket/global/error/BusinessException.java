package com.flowticket.global.error;

import lombok.Getter;

/** 도메인 예외 단일 타입. 전역 핸들러가 ErrorCode 기반으로 변환한다. */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
