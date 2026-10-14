package com.flowticket.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.flowticket.support.IntegrationTestSupport;
import com.flowticket.support.MutableClock;

import com.flowticket.event.dto.EventSummaryResponse;
import com.flowticket.event.kopis.KopisEvent;
import com.flowticket.event.kopis.KopisUpserter;
import com.flowticket.event.repository.EventRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 조회수 랭킹 검증(Testcontainers Redis). 핵심 불변식:
 * 인기(누적)와 실시간(감쇠)은 같은 조회를 다른 집계라 결과가 달라질 수 있다.
 *
 * 감쇠는 실행 횟수가 아니라 흐른 시간으로 곱한다. 그래서 시계를 직접 돌린다(한 주기 = 5분, ranking.decay-rate-ms 기본값).
 */
@SpringBootTest
@Import(RankingIntegrationTest.MutableClockConfig.class)
class RankingIntegrationTest extends IntegrationTestSupport {

    private static final Duration PERIOD = Duration.ofMinutes(5);

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired RankingService ranking;
    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;
    @Autowired KopisUpserter upserter;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired MutableClock clock;

    private Long idA;
    private Long idB;

    @BeforeEach
    void seed() {
        eventRepository.deleteAll();
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
        // A를 2회 조회(누적·실시간 모두 2). 이후 네 주기를 흘려 실시간만 끌어내린다.
        ranking.recordView(idA, "1.1.1.1");
        ranking.recordView(idA, "2.2.2.2");
        for (int i = 0; i < 4; i++) {
            decayAfter(PERIOD); // hot: 2 → 0.8192 (×0.8 4회), total은 불변
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
            decayAfter(PERIOD); // 0.8^11 ≈ 0.0859 < 0.1 → 제거
        }
        assertThat(ranking.topHot(10)).doesNotContain(idA);
        // 누적은 그대로 남음
        assertThat(ranking.topTotal(10)).contains(idA);
    }

    @Test
    void 같은_주기에_감쇠가_두번_실행돼도_한번만_줄어든다() {
        // 파드마다 5분 주기로 decay()가 돈다. 락(ranking-decay)은 lockAtLeastFor=PT0S라 실행이 끝나는 즉시 풀리고,
        // 같은 주기 안에 다른 파드의 틱이 오면 락을 다시 잡아 한 번 더 실행된다. 같은 빈을 연달아 부르는 것이 그 상황이다
        // (락 이름이 같으므로 다른 파드의 실행과 구별되지 않는다).
        ranking.recordView(idA, "1.1.1.1"); // hot=1.0
        ranking.decay(); // 파드 1의 틱(기록 없음 → 한 주기만큼)
        ranking.decay(); // 같은 주기 안 파드 2의 틱

        assertThat(hot(idA)).isCloseTo(0.8, within(1e-9)); // 한 주기 = ×0.8 한 번
    }

    @Test
    void 주기를_건너뛰면_흐른_시간만큼_감쇠한다() {
        // 틱이 늦거나 빠져도 반감기는 시간 기준으로 유지된다: 10분 = 두 주기 = ×0.64.
        ranking.recordView(idA, "1.1.1.1"); // hot=1.0
        ranking.decay(); // 기준 시각 기록(×0.8)
        decayAfter(PERIOD.multipliedBy(2));

        assertThat(hot(idA)).isCloseTo(0.8 * 0.64, within(1e-9));
    }

    @Test
    void 파드_시계가_뒤처져_보이면_감쇠하지_않는다() {
        // 다른 파드의 시계가 늦어 지금이 마지막 감쇠보다 과거로 보이면 되돌리지도, 다시 곱하지도 않는다.
        ranking.recordView(idA, "1.1.1.1"); // hot=1.0
        ranking.decay(); // ×0.8, 기준 시각 = 지금
        decayAfter(Duration.ofMinutes(-1));

        assertThat(hot(idA)).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void 검색어가_인기검색어로_집계된다() {
        ranking.recordSearch("재즈");
        ranking.recordSearch("재즈");
        ranking.recordSearch("콘서트");

        List<String> top = ranking.topKeywords(10);
        assertThat(top).containsExactly("재즈", "콘서트"); // 재즈(2) > 콘서트(1)
    }

    private void decayAfter(Duration elapsed) {
        clock.advance(elapsed);
        ranking.decay();
    }

    private Double hot(Long id) {
        return redisTemplate.opsForZSet().score(RankingService.HOT, String.valueOf(id));
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
