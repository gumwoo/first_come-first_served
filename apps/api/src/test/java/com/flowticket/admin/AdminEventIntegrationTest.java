package com.flowticket.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowticket.auth.domain.AuthProvider;
import com.flowticket.auth.domain.User;
import com.flowticket.auth.domain.UserRole;
import com.flowticket.auth.repository.UserRepository;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.security.JwtProvider;
import java.util.Map;
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
 * 운영 이벤트 관리(S07): 수동 등록(POST) → 목록/상세 노출 → 부분 수정(PATCH)로 상태 전이.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AdminEventIntegrationTest {

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
        r.add("queue.admit-interval-ms", () -> "3600000");
        r.add("seat.sweep-interval-ms", () -> "3600000");
        r.add("order.sweep-interval-ms", () -> "3600000");
    }

    @Autowired TestRestTemplate rest;
    @Autowired UserRepository userRepository;
    @Autowired EventRepository eventRepository;
    @Autowired JwtProvider jwtProvider;

    String adminToken;

    @BeforeEach
    void setup() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        eventRepository.deleteAll();
        userRepository.deleteAll();
        adminToken = jwtProvider.createAccessToken(userRepository.save(User.builder()
                .email("admin@test.com").passwordHash("x").name("t").phone(null)
                .role(UserRole.ROLE_ADMIN).provider(AuthProvider.local).marketingOptIn(false).build()));
    }

    @Test
    void 이벤트_수동등록_후_목록과_상세에_노출() {
        Long id = createEvent("뮤지컬 캣츠", "SCHEDULED");

        // 목록
        ResponseEntity<JsonNode> list = rest.exchange(
                "/admin/events", HttpMethod.GET, auth(null), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = list.getBody().get("data").get("items");
        assertThat(items).isNotEmpty();
        assertThat(items.get(0).get("title").asText()).isEqualTo("뮤지컬 캣츠");
        assertThat(items.get(0).get("fromKopis").asBoolean()).isFalse(); // 수동 등록 → kopisId 없음

        // 상세
        ResponseEntity<JsonNode> detail = rest.exchange(
                "/admin/events/" + id, HttpMethod.GET, auth(null), JsonNode.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().get("data").get("status").asText()).isEqualTo("SCHEDULED");
    }

    @Test
    void 이벤트_상태_부분수정_PATCH() {
        Long id = createEvent("연극 햄릿", "SCHEDULED");

        ResponseEntity<JsonNode> patched = rest.exchange(
                "/admin/events/" + id, HttpMethod.PATCH, auth(Map.of("status", "ON_SALE")), JsonNode.class);

        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody().get("data").get("status").asText()).isEqualTo("ON_SALE");
        assertThat(patched.getBody().get("data").get("title").asText()).isEqualTo("연극 햄릿"); // 미지정 필드는 유지
    }

    @Test
    void 제목_없이_등록하면_400() {
        ResponseEntity<String> res = rest.exchange(
                "/admin/events", HttpMethod.POST, auth(Map.of("venue", "예술의전당")), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 잘못된_상태값이면_400() {
        ResponseEntity<String> res = rest.exchange(
                "/admin/events", HttpMethod.POST, auth(Map.of("title", "x", "status", "NOPE")), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- helpers ---

    private Long createEvent(String title, String status) {
        ResponseEntity<JsonNode> res = rest.exchange(
                "/admin/events", HttpMethod.POST, auth(Map.of("title", title, "status", status)), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody().get("data").get("id").asLong();
    }

    private HttpEntity<Object> auth(Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(adminToken);
        h.add("Content-Type", "application/json");
        return new HttpEntity<>(body, h);
    }
}
