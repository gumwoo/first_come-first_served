package com.flowticket.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.support.IntegrationTestSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowticket.auth.domain.AuthProvider;
import com.flowticket.auth.domain.User;
import com.flowticket.auth.domain.UserRole;
import com.flowticket.auth.repository.UserRepository;
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

/**
 * S07 운영 알림: 임계치 조회(시드 기본값)·수정, 잘못된 값 400.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminAlertIntegrationTest extends IntegrationTestSupport {

    @Autowired TestRestTemplate rest;
    @Autowired UserRepository userRepository;
    @Autowired JwtProvider jwtProvider;

    String adminToken;

    @BeforeEach
    void setup() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        userRepository.deleteAll();
        adminToken = jwtProvider.createAccessToken(userRepository.save(User.builder()
                .email("admin@test.com").passwordHash("x").name("t").phone(null)
                .role(UserRole.ROLE_ADMIN).provider(AuthProvider.local).marketingOptIn(false).build()));
    }

    @Test
    void 알림_임계치_조회는_시드기본값_반환() {
        ResponseEntity<JsonNode> res = rest.exchange(
                "/admin/alerts", HttpMethod.GET, auth(null), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = res.getBody().get("data");
        assertThat(data.get("dlqPendingThreshold").asInt()).isEqualTo(1); // V13 시드
        assertThat(data.get("dlqPending").asLong()).isEqualTo(0L);        // DLQ 비어있음
        assertThat(data.get("breached").asBoolean()).isFalse();           // 0 >= 1 아님
    }

    @Test
    void 알림_임계치_수정은_새값_반영() {
        ResponseEntity<JsonNode> res = rest.exchange(
                "/admin/alerts", HttpMethod.PUT, auth(Map.of("dlqPendingThreshold", 5)), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("data").get("dlqPendingThreshold").asInt()).isEqualTo(5);

        // 재조회에도 반영(영속)
        ResponseEntity<JsonNode> get = rest.exchange(
                "/admin/alerts", HttpMethod.GET, auth(null), JsonNode.class);
        assertThat(get.getBody().get("data").get("dlqPendingThreshold").asInt()).isEqualTo(5);
    }

    @Test
    void 음수_임계치는_400() {
        ResponseEntity<String> res = rest.exchange(
                "/admin/alerts", HttpMethod.PUT, auth(Map.of("dlqPendingThreshold", -1)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private HttpEntity<Object> auth(Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(adminToken);
        h.add("Content-Type", "application/json");
        return new HttpEntity<>(body, h);
    }
}
