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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 운영 알림: 임계치 조회(시드 기본값)·수정, 잘못된 값 400.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminAlertIntegrationTest extends IntegrationTestSupport {

    @Autowired TestRestTemplate rest;
    @Autowired UserRepository userRepository;
    @Autowired JwtProvider jwtProvider;
    @Autowired JdbcTemplate jdbc;

    String adminToken;

    @BeforeEach
    void setup() {
        // alert_settings는 시드 테이블이라 공통 초기화(TRUNCATE)에서 빠진다. 테스트끼리 임계치가 새지 않게 기본값으로 되돌린다.
        jdbc.update("UPDATE alert_settings SET outbox_dead_threshold = 1 WHERE id = 1");
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
        assertThat(data.get("outboxDeadThreshold").asInt()).isEqualTo(1); // V20 기본값
        assertThat(data.get("outboxDead").asLong()).isEqualTo(0L);
        assertThat(data.get("outboxBreached").asBoolean()).isFalse();     // 0 >= 1 아님
        // DLQ 필드는 Kafka 제거(ADR-028)와 함께 없어졌다.
        assertThat(data.has("dlqPendingThreshold")).isFalse();
        assertThat(data.has("breached")).isFalse();
    }

    @Test
    void 알림_임계치_수정은_새값_반영() {
        ResponseEntity<JsonNode> res = rest.exchange(
                "/admin/alerts", HttpMethod.PUT, auth(Map.of("outboxDeadThreshold", 5)), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("data").get("outboxDeadThreshold").asInt()).isEqualTo(5);

        // 재조회에도 반영(영속)
        ResponseEntity<JsonNode> get = rest.exchange(
                "/admin/alerts", HttpMethod.GET, auth(null), JsonNode.class);
        assertThat(get.getBody().get("data").get("outboxDeadThreshold").asInt()).isEqualTo(5);
    }

    @Test
    void 이전_화면이_DLQ_임계치를_함께_보내도_무시하고_아웃박스_값을_반영한다() {
        // P2a 화면은 두 값을 함께 보낸다(롤링 배포 중 공존). 알 수 없는 필드는 무시된다.
        ResponseEntity<JsonNode> res = rest.exchange("/admin/alerts", HttpMethod.PUT,
                auth(Map.of("outboxDeadThreshold", 3, "dlqPendingThreshold", 9)), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("data").get("outboxDeadThreshold").asInt()).isEqualTo(3);
    }

    @Test
    void 아웃박스_임계치가_없으면_400() {
        ResponseEntity<String> res = rest.exchange(
                "/admin/alerts", HttpMethod.PUT, auth(Map.of("dlqPendingThreshold", 5)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 음수_임계치는_400() {
        ResponseEntity<String> res = rest.exchange(
                "/admin/alerts", HttpMethod.PUT, auth(Map.of("outboxDeadThreshold", -1)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private HttpEntity<Object> auth(Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(adminToken);
        h.add("Content-Type", "application/json");
        return new HttpEntity<>(body, h);
    }
}
