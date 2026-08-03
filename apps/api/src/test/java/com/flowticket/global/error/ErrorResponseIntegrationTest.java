package com.flowticket.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 클라이언트 입력 문제를 <b>서버 오류(500)로 보고하지 않는지</b> 검증.
 *
 * <p>운영 이미지로 검증하다 발견했다: 매핑이 없는 경로와 깨진 요청 본문이 모두 fallback 핸들러로 떨어져
 * 500 + 스택트레이스 ERROR 로그가 남았다. 오탈자·봇 스캔 같은 정상 트래픽이 서버 오류로 집계되고
 * 계약(error-codes.yaml: NOT_FOUND=404)과도 어긋난다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorResponseIntegrationTest extends IntegrationTestSupport {

    @Autowired TestRestTemplate rest;

    @Test
    void 공개_경로의_없는_주소는_404_NOT_FOUND() {
        // 인증이 필요한 경로는 시큐리티가 먼저 401을 준다(경로 존재 여부를 노출하지 않음 — 바람직).
        // 이 결함이 드러나는 건 **공개 경로**다: 시큐리티를 통과해 디스패처까지 가서
        // NoResourceFoundException → (예전엔) 500이 됐다.
        ResponseEntity<String> res =
                rest.exchange("/events/1/no-such-sub", HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("NOT_FOUND");
    }

    @Test
    void 깨진_요청본문은_400_VALIDATION_ERROR() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // JSON으로 파싱할 수 없는 본문 — 클라이언트 입력 문제이지 서버 오류가 아니다.
        HttpEntity<String> body = new HttpEntity<>("{\"email\": ", headers);

        ResponseEntity<String> res = rest.exchange("/auth/login", HttpMethod.POST, body, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("VALIDATION_ERROR");
    }
}
