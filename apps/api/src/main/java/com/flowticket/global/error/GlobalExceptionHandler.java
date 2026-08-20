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
     * 검증 실패 → 400. <b>상위 타입인 {@code BindException}으로 받는다.</b>
     *
     * <pre>
     * Exception
     *   └─ BindException                       ← @ModelAttribute + <b>setter 바인딩</b>(가변 빈)
     *        └─ MethodArgumentNotValidException ← @RequestBody, 그리고
     *                                             @ModelAttribute + <b>생성자 바인딩</b>(record)
     * </pre>
     *
     * <p>⚠️ 이 계층은 <b>측정으로 확인했다.</b> "@ModelAttribute면 BindException"이라는 통설은
     * 가변 빈에만 맞고, {@code PageQuery} 같은 record는 생성자 바인딩이라 Spring 6.1이
     * {@code MethodArgumentNotValidException}을 던진다(MockMvc로 실제 resolvedException 확인).
     *
     * <p>그래서 record만 쓰는 지금은 하위 타입만 잡아도 동작한다. 그럼에도 상위 타입으로 받는 이유는
     * <b>가변 DTO가 하나라도 추가되는 순간 그쪽이 조용히 500으로 떨어지기 때문</b>이다 —
     * 이 핸들러가 이미 두 번 겪은 실패 모드다(NoResourceFoundException·HttpMessageNotReadableException).
     * 본문 구성은 그대로다 — {@code BindException}에도 {@code getBindingResult()}가 있다.
     *
     * <p>{@code @RequestParam}에 제약을 다는 방식({@code @Validated} + {@code @Min})은 쓰지 않았다.
     * 그쪽은 {@code ConstraintViolationException}을 던지는데 이 저장소에 그 핸들러가 없어
     * 결국 500이 된다.
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
     * 존재하지 않는 경로 → 404. 매핑이 없으면 정적 리소스 처리로 넘어가 {@code NoResourceFoundException}이
     * 나는데, 이걸 안 잡으면 아래 fallback이 <b>500 + 스택트레이스 ERROR 로그</b>로 처리한다.
     * 오탈자·봇 스캔 같은 정상적인 "없는 주소" 요청이 서버 오류로 보고되고 로그를 채우는 문제가 있었다.
     * (계약상으로도 NOT_FOUND=404다 — 500은 우리 error-codes.yaml과 어긋난다.)
     *
     * <p>인증이 필요한 경로는 시큐리티가 먼저 401을 주므로 여기까지 오지 않는다(경로 존재 여부를
     * 노출하지 않는 편이 낫다). 이 처리가 실제로 필요한 건 <b>공개 경로</b>(events·queue 등)다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        log.debug("존재하지 않는 경로: {}", e.getResourcePath()); // 스택트레이스 없이 debug — 정상 트래픽
        ErrorCode code = ErrorCode.NOT_FOUND;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), code.getDefaultMessage()));
    }

    /**
     * 본문이 JSON으로 읽히지 않음(깨진 인코딩·형식 오류) → 400. 클라이언트 입력 문제이므로 서버 오류가 아니다.
     * 실제로 운영 이미지 검증 중 잘못된 인코딩으로 보낸 요청이 500으로 응답되는 것을 확인해 분리했다.
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
