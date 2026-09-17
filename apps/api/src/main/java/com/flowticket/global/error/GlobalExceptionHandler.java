package com.flowticket.global.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 모든 예외를 공통 { error } 포맷으로 변환. 컨트롤러는 try/catch 하지 않는다. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), e.getMessage()));
    }

    /**
     * 검증 실패 → 400. 하위 타입(MethodArgumentNotValidException)이 아니라 BindException으로
     * 받는다. 가변 DTO가 추가되면 그쪽은 BindException을 던져 500으로 떨어진다(api-rules.md §4).
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleValidation(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse(ErrorCode.VALIDATION_ERROR.getDefaultMessage());
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR.name(), message));
    }

    /** 필수 쿼리/폼 파라미터 누락 → 400(서버 오류 아님). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        String message = e.getParameterName() + " 파라미터가 필요합니다.";
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR.name(), message));
    }

    /** 파라미터 타입 불일치(예: 숫자 자리에 문자) → 400. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = e.getName() + " 파라미터 형식이 올바르지 않습니다.";
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR.name(), message));
    }

    /**
     * 존재하지 않는 경로 → 404. 매핑이 없으면 NoResourceFoundException이 나고, 잡지 않으면
     * fallback이 500 + ERROR 로그로 처리해 오탈자·봇 스캔이 서버 오류로 보고된다.
     *
     * 인증이 필요한 경로는 시큐리티가 먼저 401을 주므로 여기까지 오지 않는다.
     * 이 처리가 필요한 건 공개 경로(events·queue 등)다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        log.debug("존재하지 않는 경로: {}", e.getResourcePath()); // 스택트레이스 없이 debug: 정상 트래픽
        ErrorCode code = ErrorCode.NOT_FOUND;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), code.getDefaultMessage()));
    }

    /**
     * 본문이 JSON으로 읽히지 않음(깨진 인코딩·형식 오류) → 400. 클라이언트 입력 문제다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.debug("요청 본문을 읽을 수 없음: {}", e.getMostSpecificCause().getMessage());
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), "요청 본문 형식이 올바르지 않습니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), code.getDefaultMessage()));
    }
}
