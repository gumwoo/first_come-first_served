package com.flowticket.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowticket.auth.domain.AuthProvider;
import com.flowticket.auth.domain.User;
import com.flowticket.auth.domain.UserRole;
import com.flowticket.auth.repository.UserRepository;
import com.flowticket.global.security.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 관리자 인증 게이트(S07 Phase 1): /admin/** 은 ROLE_ADMIN 전용.
 * 미인증→401, 일반회원(ROLE_USER)→403, 관리자(ROLE_ADMIN)→200.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AdminAuthIntegrationTest {

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
        r.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired TestRestTemplate rest;
    @Autowired UserRepository userRepository;
    @Autowired JwtProvider jwtProvider;

    @BeforeEach
    void setup() {
        // 401을 정상 응답으로 받기 위한 팩토리(레거시 HttpURLConnection 재시도 회피).
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        userRepository.deleteAll();
    }

    @Test
    void 미인증_admin_대시보드는_401() {
        ResponseEntity<String> res = rest.exchange("/admin/dashboard", HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 일반회원은_admin_대시보드_403() {
        String token = tokenFor(UserRole.ROLE_USER, "user@test.com");
        ResponseEntity<String> res = rest.exchange("/admin/dashboard", HttpMethod.GET, bearer(token), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void 관리자는_admin_대시보드_200_집계반환() {
        String token = tokenFor(UserRole.ROLE_ADMIN, "admin@test.com");
        ResponseEntity<JsonNode> res = rest.exchange("/admin/dashboard", HttpMethod.GET, bearer(token), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("data").has("totalEvents")).isTrue();
        assertThat(res.getBody().get("data").get("kafkaConnected").asBoolean()).isFalse();
    }

    // --- helpers ---

    private String tokenFor(UserRole role, String email) {
        User u = userRepository.save(User.builder()
                .email(email).passwordHash("x").name("t").phone(null)
                .role(role).provider(AuthProvider.local).marketingOptIn(false).build());
        return jwtProvider.createAccessToken(u);
    }

    private HttpEntity<Void> bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }
}
