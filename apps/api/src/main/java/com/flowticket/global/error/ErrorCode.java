package com.flowticket.global.error;

import org.springframework.http.HttpStatus;

/**
 * 에러코드 단일 정의. contracts/error-codes.yaml 과 일치해야 한다(하네스 검사).
 */
public enum ErrorCode {
    // 인증/인가
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
    // 검증
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    // 가입/계정 (S01)
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    DUPLICATE_PHONE(HttpStatus.CONFLICT, "이미 가입된 휴대폰 번호입니다."),
    REGISTRATION_TERMS_NOT_ACCEPTED(HttpStatus.BAD_REQUEST, "필수 약관에 동의해야 합니다."),
    LOCAL_LOGIN_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "소셜 계정은 이메일/비밀번호 로그인을 사용할 수 없습니다."),
    // 휴대폰 인증 (S01)
    PHONE_VERIFICATION_REQUIRED(HttpStatus.BAD_REQUEST, "휴대폰 인증이 필요합니다."),
    PHONE_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST, "인증번호가 올바르지 않거나 만료되었습니다."),
    PHONE_VERIFICATION_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "인증번호 발송 횟수를 초과했습니다."),
    // 토큰 생명주기 / RTR (S01)
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 만료되었습니다."),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "재사용된 토큰이 감지되었습니다. 다시 로그인해 주세요."),
    ACCESS_TOKEN_BLACKLISTED(HttpStatus.UNAUTHORIZED, "로그아웃된 토큰입니다."),
    // 선착순 핵심
    SOLD_OUT(HttpStatus.CONFLICT, "매진되었습니다."),
    QUEUE_EXPIRED(HttpStatus.GONE, "대기시간이 만료되었습니다."),
    HOLD_EXPIRED(HttpStatus.GONE, "좌석 선점이 만료되었습니다."),
    QUEUE_NOT_ADMITTED(HttpStatus.FORBIDDEN, "대기열 입장이 허용되지 않았습니다."),
    MAX_PER_USER_EXCEEDED(HttpStatus.CONFLICT, "1인 구매 가능 수량을 초과했습니다."),
    DUPLICATE_BOOKING(HttpStatus.CONFLICT, "이미 예매한 회차입니다."),
    // 결제
    PAYMENT_FAILED(HttpStatus.PAYMENT_REQUIRED, "결제에 실패했습니다."),
    PAYMENT_TIMEOUT(HttpStatus.REQUEST_TIMEOUT, "결제 시간이 초과되었습니다."),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "허용되지 않은 상태 변경입니다."),
    // 환불
    REFUND_NOT_ALLOWED(HttpStatus.CONFLICT, "환불할 수 없는 상태입니다."),
    // 공통
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
