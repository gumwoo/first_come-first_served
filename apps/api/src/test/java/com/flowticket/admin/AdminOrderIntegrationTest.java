package com.flowticket.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowticket.auth.domain.AuthProvider;
import com.flowticket.auth.domain.User;
import com.flowticket.auth.domain.UserRole;
import com.flowticket.auth.repository.UserRepository;
import com.flowticket.global.security.JwtProvider;
import com.flowticket.order.domain.Order;
import com.flowticket.order.repository.OrderRepository;
import java.time.LocalDateTime;
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
 * 운영 주문 조회(S07 Phase 2): 관리자는 전 사용자 주문을 주문자 이메일과 함께 조회하고, 상태 필터가 동작한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AdminOrderIntegrationTest {

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
    @Autowired OrderRepository orderRepository;
    @Autowired JwtProvider jwtProvider;

    @BeforeEach
    void setup() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        orderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void 관리자는_전사용자_주문을_주문자이메일과_함께_조회() {
        User buyer = userRepository.save(user("buyer@test.com", UserRole.ROLE_USER));
        orderRepository.save(order(buyer.getId()));
        String adminToken = jwtProvider.createAccessToken(userRepository.save(user("admin@test.com", UserRole.ROLE_ADMIN)));

        ResponseEntity<JsonNode> res = rest.exchange(
                "/admin/orders", HttpMethod.GET, bearer(adminToken), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = res.getBody().get("data").get("content");
        assertThat(content).isNotEmpty();
        assertThat(content.get(0).get("userEmail").asText()).isEqualTo("buyer@test.com");
        assertThat(content.get(0).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void 상태필터_불일치면_빈목록() {
        User buyer = userRepository.save(user("buyer@test.com", UserRole.ROLE_USER));
        orderRepository.save(order(buyer.getId())); // PENDING
        String adminToken = jwtProvider.createAccessToken(userRepository.save(user("admin@test.com", UserRole.ROLE_ADMIN)));

        ResponseEntity<JsonNode> res = rest.exchange(
                "/admin/orders?status=PAID", HttpMethod.GET, bearer(adminToken), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("data").get("content")).isEmpty();
    }

    @Test
    void 잘못된_상태값은_400() {
        String adminToken = jwtProvider.createAccessToken(userRepository.save(user("admin@test.com", UserRole.ROLE_ADMIN)));
        ResponseEntity<String> res = rest.exchange(
                "/admin/orders?status=NOPE", HttpMethod.GET, bearer(adminToken), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- helpers ---

    private User user(String email, UserRole role) {
        return User.builder().email(email).passwordHash("x").name("t").phone(null)
                .role(role).provider(AuthProvider.local).marketingOptIn(false).build();
    }

    private Order order(Long userId) {
        return Order.builder()
                .eventId(1L).userId(userId).holdId(1L).amount(50000)
                .expiresAt(LocalDateTime.now().plusMinutes(10)).build();
    }

    private HttpEntity<Void> bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }
}
