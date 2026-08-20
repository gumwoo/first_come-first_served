package com.flowticket.global.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowticket.dlq.controller.AdminDlqController;
import com.flowticket.dlq.service.AdminDlqService;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.global.error.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * 페이징 입력 검증이 <b>HTTP 경계에서</b> 400으로 나가는지 본다.
 *
 * <p>핸들러를 직접 호출하는 단위 테스트로는 부족하다. 이 결함의 본질은 "어떤 예외가 던져지고
 * 그것이 어느 핸들러로 라우팅되는가"이므로, 예외 타입을 가정하지 않고 <b>요청 → 응답</b>으로
 * 확인해야 한다. 컨테이너 없이 도는 standalone MockMvc를 쓴다.
 *
 * <p>⚠️ standalone MockMvc는 Boot 자동설정을 타지 않아 {@code Accept}가 없으면 클래스패스
 * 순서대로 XML로 협상한다(운영은 JSON이 기본). 그래서 요청마다 명시한다.
 *
 * <p>대표로 {@code AdminDlqController}를 쓴다 — 6개 엔드포인트가 모두 같은 {@link PageQuery}를
 * 받으므로 바인딩·검증 경로는 동일하다.
 */
class PaginationValidationTest {

    private AdminDlqService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(AdminDlqService.class);
        when(service.list(any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0));

        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new AdminDlqController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    /**
     * <b>수정 전에 왜 500이었는지</b>를 남긴다. 회귀 가드가 아니라 근거 문서다 — 검증이 없으면
     * 잘못된 값이 {@code PageRequest.of()}까지 내려가고, 거기서 나는 예외에는 전용 핸들러가 없어
     * fallback이 서버 오류로 처리한다. 클라이언트 입력 오류가 500 + ERROR 로그가 되던 경로다.
     */
    @Test
    @DisplayName("경계에서 막지 않으면 잘못된 페이징 입력은 500이 된다")
    void 검증이_없으면_500이_된다() {
        assertThatThrownBy(() -> PageRequest.of(-1, PageQuery.DEFAULT_SIZE))
                .as("음수 page는 Spring Data가 거부한다")
                .isInstanceOf(IllegalArgumentException.class);

        var res = new GlobalExceptionHandler()
                .handleUnexpected(new IllegalArgumentException("Page index must not be less than zero"));

        assertThat(res.getStatusCode())
                .as("IllegalArgumentException 전용 핸들러가 없어 fallback이 잡는다")
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
    }

    @Test
    @DisplayName("음수 page는 500이 아니라 400")
    void 음수_page는_400() throws Exception {
        mvc.perform(get("/admin/dlq").param("page", "-1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.VALIDATION_ERROR.name()))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("page")));
    }

    @Test
    @DisplayName("상한을 넘는 size는 400 — 무제한 조회를 경계에서 막는다")
    void 과도한_size는_400() throws Exception {
        mvc.perform(get("/admin/dlq").param("size", "1000000").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("size")));
    }

    @Test
    @DisplayName("size=0도 막는다 — PageRequest가 거부하는 값이다")
    void size_0은_400() throws Exception {
        mvc.perform(get("/admin/dlq").param("size", "0").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("정상 범위는 그대로 통과하고 서비스에 값이 전달된다")
    void 정상_범위는_통과한다() throws Exception {
        mvc.perform(get("/admin/dlq").param("page", "2").param("size", "50").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(service).list(null, 2, 50);
    }

    @Test
    @DisplayName("파라미터를 생략하면 기본값이 쓰인다 — 기존 호출자의 계약이 바뀌지 않는다")
    void 생략시_기본값() throws Exception {
        mvc.perform(get("/admin/dlq").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(service).list(null, PageQuery.DEFAULT_PAGE, PageQuery.DEFAULT_SIZE);
    }
}
