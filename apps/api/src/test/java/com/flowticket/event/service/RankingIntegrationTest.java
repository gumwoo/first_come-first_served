package com.flowticket.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.event.dto.EventSummaryResponse;
import com.flowticket.event.kopis.KopisEvent;
import com.flowticket.event.kopis.KopisUpserter;
import com.flowticket.event.repository.EventRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.flowticket.support.SharedContainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 조회수 랭킹 검증(Testcontainers Redis). 핵심 불변식:
 * 인기(누적)와 실시간(감쇠)은 같은 조회를 다른 집계로 → 결과가 달라질 수 있다.
 */
@SpringBootTest
class RankingIntegrationTest {

    static final PostgreSQLContainer<?> postgres = SharedContainers.POSTGRES;

    static final GenericContainer<?> redis = SharedContainers.REDIS;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("jwt.secret", () -> "integration-test-secret-0123456789-0123456789-0123456789");
        r.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        r.add("ranking.decay-rate-ms", () -> "3600000"); // 자동 감쇠 비활성(테스트는 수동 호출)
    }

    @Autowired RankingService ranking;
    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;
    @Autowired KopisUpserter upserter;
    @Autowired StringRedisTemplate redisTemplate;

    private Long idA;
    private Long idB;

    @BeforeEach
    void seed() {
        eventRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        upserter.upsertAll(List.of(
                kopis("PFA", "공연 A", "공연중"),
                kopis("PFB", "공연 B", "공연중")));
        idA = eventRepository.findByKopisId("PFA").orElseThrow().getId();
        idB = eventRepository.findByKopisId("PFB").orElseThrow().getId();
    }

    @Test
    void 같은_IP_60초내_재조회는_중복으로_카운트되지_않는다() {
        ranking.recordView(idA, "1.1.1.1");
        ranking.recordView(idA, "1.1.1.1"); // 중복

        Double score = redisTemplate.opsForZSet().score(RankingService.TOTAL, String.valueOf(idA));
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void 조회수가_많은_공연이_인기_TOP_상위() {
        ranking.recordView(idA, "1.1.1.1");
        ranking.recordView(idA, "2.2.2.2");
        ranking.recordView(idB, "3.3.3.3");

        List<EventSummaryResponse> popular = eventService.popular();
        assertThat(popular.get(0).id()).isEqualTo(idA); // A(2) > B(1)
    }

    @Test
    void 누적과_실시간은_다른_결과를_낼_수_있다() {
        // A를 2회 조회(누적·실시간 모두 2). 이후 감쇠를 반복해 실시간만 끌어내린다.
        ranking.recordView(idA, "1.1.1.1");
        ranking.recordView(idA, "2.2.2.2");
        for (int i = 0; i < 4; i++) {
            ranking.decay(); // hot: 2 → 0.8192 (×0.8 4회), total은 불변
        }
        // 감쇠 후 B를 새로 조회 → 실시간 hot B=1.0 > A=0.819
        ranking.recordView(idB, "3.3.3.3");

        // 인기(누적): A(2) > B(1) → A 상위 유지
        assertThat(eventService.popular().get(0).id()).isEqualTo(idA);
        // 실시간(감쇠): B가 A를 추월
        assertThat(eventService.realtimeRanking().get(0).id()).isEqualTo(idB);
    }

    @Test
    void 감쇠로_임계미만_항목은_랭킹에서_제거된다() {
        ranking.recordView(idA, "1.1.1.1"); // hot=1.0
        for (int i = 0; i < 11; i++) {
            ranking.decay(); // 0.8^11 ≈ 0.0859 < 0.1 → 제거
        }
        assertThat(ranking.topHot(10)).doesNotContain(idA);
        // 누적은 그대로 남음
        assertThat(ranking.topTotal(10)).contains(idA);
    }

    @Test
    void 검색어가_인기검색어로_집계된다() {
        ranking.recordSearch("재즈");
        ranking.recordSearch("재즈");
        ranking.recordSearch("콘서트");

        List<String> top = ranking.topKeywords(10);
        assertThat(top).containsExactly("재즈", "콘서트"); // 재즈(2) > 콘서트(1)
    }

    private static KopisEvent kopis(String id, String title, String state) {
        KopisEvent k = new KopisEvent();
        k.kopisId = id;
        k.title = title;
        k.venue = "테스트홀";
        k.genre = "대중음악";
        k.startDate = "2026.07.01";
        k.endDate = "2026.07.03";
        k.state = state;
        return k;
    }
}
