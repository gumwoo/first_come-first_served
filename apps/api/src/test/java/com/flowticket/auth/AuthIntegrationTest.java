package com.flowticket.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 가입→로그인→/me→로그아웃→블랙리스트, 중복가입, RTR 재사용 탐지를
 * 실제 Postgres + Redis 위에서 HTTP로 검증한다. (단위 테스트가 못 잡는 통합 동작)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("jwt.secret", () -> "integration-test-secret-0123456789-0123456789-0123456789");
        r.add("spring.kafka.bootstrap-servers", () -> "localhost:59092"); // 미사용
    }

    @Autowired
    TestRestTemplate rest;

    @org.junit.jupiter.api.BeforeEach
    void useJdkHttpClient() {
        // 레거시 HttpURLConnection은 POST 후 401을 받으면 자동 인증 재시도로
        // HttpRetryException을 던진다. Java 11+ HttpClient 기반 팩토리는 401을
        // 정상 응답으로 반환하므로 이를 사용한다. (spring-web 내장, 추가 의존성 없음)
        rest.getRestTemplate().setRequestFactory(
                new org.springframework.http.client.JdkClientHttpRequestFactory());
    }

    private void verifyPhone(String phone) {
        rest.postForEntity("/auth/phone/request", body(Map.of("phone", phone)), String.class);
        rest.postForEntity("/auth/phone/verify", body(Map.of("phone", phone, "code", "123456")), String.class);
    }

    private HttpEntity<Map<String, ?>> body(Map<String, ?> m) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(m, h);
    }

    @Test
    void 가입_로그인_me_로그아웃_블랙리스트_플로우() {
        String email = "flow@test.com";
        String phone = "01011112222";
        verifyPhone(phone);

        ResponseEntity<String> signup = rest.postForEntity("/auth/signup",
                body(Map.of("email", email, "password", "password1", "name", "홍길동",
                        "phone", phone, "termsAccepted", true)), String.class);
        assertThat(signup.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> login = rest.postForEntity("/auth/login",
                body(Map.of("email", email, "password", "password1")), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String access = login.getBody().get("data").get("accessToken").asText();

        // 보호 API 접근
        ResponseEntity<JsonNode> me = rest.exchange("/me",
                org.springframework.http.HttpMethod.GET, bearer(access), JsonNode.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("data").get("email").asText()).isEqualTo(email);

        // 로그아웃 → 블랙리스트
        rest.exchange("/auth/logout", org.springframework.http.HttpMethod.POST, bearer(access), String.class);
        ResponseEntity<String> afterLogout = rest.exchange("/me",
                org.springframework.http.HttpMethod.GET, bearer(access), String.class);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 이메일_중복_가입은_409() {
        String phone1 = "01033334444";
        String phone2 = "01055556666";
        verifyPhone(phone1);
        rest.postForEntity("/auth/signup", body(Map.of("email", "dup@test.com", "password", "password1",
                "name", "n", "phone", phone1, "termsAccepted", true)), String.class);

        verifyPhone(phone2);
        ResponseEntity<JsonNode> dup = rest.postForEntity("/auth/signup",
                body(Map.of("email", "dup@test.com", "password", "password1",
                        "name", "n", "phone", phone2, "termsAccepted", true)), JsonNode.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody().get("error").get("code").asText()).isEqualTo("DUPLICATE_EMAIL");
    }

    @Test
    void RTR_재사용된_refresh는_REUSED_감지() {
        String email = "rtr@test.com";
        String phone = "01077778888";
        verifyPhone(phone);
        rest.postForEntity("/auth/signup", body(Map.of("email", email, "password", "password1",
                "name", "n", "phone", phone, "termsAccepted", true)), String.class);

        ResponseEntity<JsonNode> login = rest.postForEntity("/auth/login",
                body(Map.of("email", email, "password", "password1")), JsonNode.class);
        String oldRefresh = login.getBody().get("data").get("refreshToken").asText();

        // 1회 회전 — oldRefresh는 이제 폐기됨
        ResponseEntity<JsonNode> rotated = rest.postForEntity("/auth/refresh",
                body(Map.of("refreshToken", oldRefresh)), JsonNode.class);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 폐기된 oldRefresh 재사용 → REUSED
        ResponseEntity<JsonNode> reused = rest.postForEntity("/auth/refresh",
                body(Map.of("refreshToken", oldRefresh)), JsonNode.class);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reused.getBody().get("error").get("code").asText()).isEqualTo("REFRESH_TOKEN_REUSED");
    }

    private HttpEntity<Void> bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }
}
