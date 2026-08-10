package com.flowticket.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.dto.EventSummaryResponse;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.event.repository.EventSearchCondition;
import com.flowticket.support.IntegrationTestSupport;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import jakarta.persistence.EntityManagerFactory;

/**
 * 목록 조회가 <b>엔티티를 로드하지 않는다</b>는 것을 회귀로 고정한다.
 *
 * <p>목록이 쓰는 컬럼은 9개인데 {@code selectFrom(event)}는 엔티티의 모든 컬럼(20개)을 읽는다.
 * V16에서 KOPIS 상세 필드(TEXT 4개)가 붙으며 폭이 더 넓어졌다.
 *
 * <p>이 테스트는 <b>성능을 단언하지 않는다</b> — 읽는 컬럼 수만 고정한다. 목록 지연이 늘어난
 * 관측이 있었지만 EXPLAIN으로는 쿼리 형태별 차이가 0.1~0.2ms에 그쳐 원인으로 확인되지 않았다.
 *
 * <p>이 테스트는 Hibernate 통계의 <b>엔티티 로드 수</b>를 본다. 프로젝션이면 0이고,
 * {@code selectFrom(event)}로 되돌리면 로드 수가 올라가 실패한다.
 */
@SpringBootTest
// 통계를 켜지 않으면 카운트가 항상 0이라 테스트가 무조건 통과하는 거짓 안전이 된다.
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.generate_statistics=true"})
class EventListProjectionTest extends IntegrationTestSupport {

    @Autowired EventRepository eventRepository;
    @Autowired EntityManagerFactory emf;

    private Statistics stats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    void seed() {
        eventRepository.deleteAll();
        for (int i = 0; i < 5; i++) {
            Event e = eventRepository.save(Event.builder()
                    .kopisId("PF-P" + i).title("공연" + i).venue("홀").region("서울특별시")
                    .genre("연극").status(EventStatus.ON_SALE).build());
            // 목록이 읽으면 안 되는 본문 텍스트를 채워둔다.
            e.updateDetail("120분", "전체", "전석 30,000원", "출연진", "아주 긴 줄거리".repeat(50), "매일 19시");
            eventRepository.saveAndFlush(e);
        }
        stats().setStatisticsEnabled(true);
        stats().clear();
    }

    /** 통계가 실제로 동작하는지 먼저 확인한다 — 안 켜져 있으면 위 단언이 무의미해진다. */
    @Test
    void 통계가_켜져있다_엔티티_조회는_카운트에_잡힌다() {
        eventRepository.findAll();
        assertThat(stats().getEntityLoadCount())
                .as("엔티티 로드가 카운트돼야 아래 '0건' 단언이 의미를 갖는다")
                .isGreaterThan(0);
    }

    @Test
    void 목록조회는_엔티티를_로드하지_않는다() {
        var condition = new EventSearchCondition(null, null, null, EventStatus.ON_SALE, null, null);

        var page = eventRepository.search(condition, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(5);
        assertThat(stats().getEntityLoadCount())
                .as("목록은 필요한 컬럼만 SELECT해야 한다 — selectFrom(event)로 되돌리면 여기서 잡힌다")
                .isZero();
    }

    @Test
    void 요약에_필요한_필드는_그대로_채워진다() {
        var condition = new EventSearchCondition(null, null, null, EventStatus.ON_SALE, null, null);

        List<EventSummaryResponse> items = eventRepository
                .search(condition, PageRequest.of(0, 20)).getContent();

        assertThat(items).allSatisfy(s -> {
            assertThat(s.id()).isNotNull();
            assertThat(s.title()).startsWith("공연");
            assertThat(s.venue()).isEqualTo("홀");
            assertThat(s.region()).isEqualTo("서울특별시");
            assertThat(s.genre()).isEqualTo("연극");
            assertThat(s.status()).isEqualTo("ON_SALE"); // enum → name() 매핑 확인
        });
    }
}
