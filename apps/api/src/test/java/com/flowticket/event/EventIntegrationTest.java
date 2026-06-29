package com.flowticket.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowticket.event.dto.EventSummaryResponse;
import com.flowticket.event.kopis.KopisEvent;
import com.flowticket.event.kopis.KopisUpserter;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.event.service.EventService;
import com.flowticket.global.common.PageResponse;
import com.flowticket.global.error.BusinessException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * KOPIS upsert → 목록/검색 필터 → 상세 조회를 실제 Postgres 위에서 검증한다.
 * 외부 KOPIS 호출은 KopisUpserter에 항목을 직접 주입해 배제한다(결정적 테스트).
 */
@SpringBootTest
@Testcontainers
class EventIntegrationTest {

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

    @Autowired EventService eventService;
    @Autowired KopisUpserter upserter;
    @Autowired EventRepository eventRepository;

    @BeforeEach
    void seed() {
        eventRepository.deleteAll();
        upserter.upsertAll(List.of(
                kopis("PF001", "2026 여름 콘서트", "대중음악", "서울특별시", "공연중"),  // ON_SALE
                kopis("PF002", "겨울 재즈의 밤", "대중음악", "부산광역시", "공연예정"),  // SCHEDULED
                kopis("PF003", "셰익스피어 연극", "연극", "서울특별시", "공연중")));     // ON_SALE
    }

    @Test
    void upsert는_kopisId_기준_저장하고_한글이_보존된다() {
        assertThat(eventRepository.count()).isEqualTo(3);
        var saved = eventRepository.findByKopisId("PF001").orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("2026 여름 콘서트");
        assertThat(saved.getGenre()).isEqualTo("대중음악");
        assertThat(saved.getRegion()).isEqualTo("서울특별시");
    }

    @Test
    void 검색은_지역으로_필터된다_시도전체명에_짧은라벨_contains() {
        // "서울" → "서울특별시" contains 매칭
        PageResponse<EventSummaryResponse> page =
                eventService.searchByKeyword(null, null, "서울", null, 0, 20);
        assertThat(page.items()).extracting(EventSummaryResponse::title)
                .containsExactlyInAnyOrder("2026 여름 콘서트", "셰익스피어 연극");
    }

    @Test
    void upsert는_같은_kopisId면_갱신만_한다() {
        upserter.upsertAll(List.of(kopis("PF001", "수정된 콘서트", "대중음악", "공연완료")));
        assertThat(eventRepository.count()).isEqualTo(3); // 신규 X
        var updated = eventRepository.findByKopisId("PF001").orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("수정된 콘서트");
    }

    @Test
    void 검색은_장르로_필터된다() {
        PageResponse<EventSummaryResponse> page =
                eventService.searchByKeyword(null, "연극", null, null, 0, 20);
        assertThat(page.items()).extracting(EventSummaryResponse::title)
                .containsExactly("셰익스피어 연극");
    }

    @Test
    void 검색은_상태로_필터된다() {
        PageResponse<EventSummaryResponse> page =
                eventService.searchByKeyword(null, null, null, "ON_SALE", 0, 20);
        assertThat(page.items()).hasSize(2)
                .extracting(EventSummaryResponse::status).containsOnly("ON_SALE");
    }

    @Test
    void 검색은_키워드와_장르를_동시에_적용한다() {
        PageResponse<EventSummaryResponse> page =
                eventService.searchByKeyword("재즈", "대중음악", null, null, 0, 20);
        assertThat(page.items()).extracting(EventSummaryResponse::title)
                .containsExactly("겨울 재즈의 밤");
    }

    @Test
    void 잘못된_상태값은_검증오류() {
        assertThatThrownBy(() -> eventService.searchByKeyword(null, null, null, "NOPE", 0, 20))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 없는_상세는_NOT_FOUND() {
        assertThatThrownBy(() -> eventService.detail(999_999L))
                .isInstanceOf(BusinessException.class);
    }

    private static KopisEvent kopis(String id, String title, String genre, String region, String state) {
        KopisEvent k = new KopisEvent();
        k.kopisId = id;
        k.title = title;
        k.venue = "테스트홀";
        k.region = region;
        k.genre = genre;
        k.posterUrl = "http://img/" + id + ".jpg";
        k.startDate = "2026.07.01";
        k.endDate = "2026.07.03";
        k.state = state;
        return k;
    }
}
