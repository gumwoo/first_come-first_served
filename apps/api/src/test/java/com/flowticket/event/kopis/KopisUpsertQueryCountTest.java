package com.flowticket.event.kopis;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.support.IntegrationTestSupport;
import org.springframework.test.context.TestPropertySource;

import com.flowticket.event.domain.Event;
import com.flowticket.event.repository.EventRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * IMP-012 측정: KOPIS 동기화의 <b>기존 여부 확인 쿼리 수</b>를 before/after로 센다.
 * before(naive)는 항목마다 {@code findByKopisId} → N건에 SELECT N번,
 * after는 {@code findAllByKopisIdIn} 배치 조회 → 1번.
 *
 * <p>쿼리 수는 Hibernate 통계(prepare statement count)로 직접 측정한다(추정 아님).
 */
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.generate_statistics=true"})
@SpringBootTest
class KopisUpsertQueryCountTest extends IntegrationTestSupport {

    private static final int N = 200;

    @Autowired EventRepository eventRepository;
    @Autowired KopisUpserter upserter;
    @Autowired EntityManagerFactory emf;

    private Statistics stats;
    private List<String> kopisIds;

    @BeforeEach
    void seed() {
        eventRepository.deleteAll();
        kopisIds = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            String id = "KOPIS-" + i;
            kopisIds.add(id);
            eventRepository.save(Event.builder().kopisId(id).title("공연 " + i).genre("연극").build());
        }
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
    }

    @Test
    void IMP012_기존여부_확인_쿼리수_before_after() {
        // before(naive): 항목마다 존재 확인 → SELECT N번
        stats.clear();
        for (String id : kopisIds) {
            eventRepository.findByKopisId(id);
        }
        long before = stats.getPrepareStatementCount();

        // after: 배치 조회 1번
        stats.clear();
        List<Event> found = eventRepository.findAllByKopisIdIn(kopisIds);
        long after = stats.getPrepareStatementCount();

        assertThat(found).hasSize(N);
        assertThat(before).as("naive: 항목당 SELECT").isEqualTo(N);
        assertThat(after).as("배치: 한 번의 SELECT").isEqualTo(1);
    }

    @Test
    void 배치내_중복_kopisId는_한_번만_반영된다() {
        // KOPIS 응답에 같은 공연이 중복으로 오면 예전 구조는 INSERT가 두 번 나가 UNIQUE 위반이 됐다.
        eventRepository.deleteAll();
        KopisEvent a = kopisEvent("DUP-1", "제목 A");
        KopisEvent b = kopisEvent("DUP-1", "제목 B"); // 같은 kopisId, 나중 값

        int processed = upserter.upsertAll(List.of(a, b));

        assertThat(processed).isEqualTo(1);
        assertThat(eventRepository.findByKopisId("DUP-1").orElseThrow().getTitle())
                .as("뒤에 온 값으로 덮어쓴다").isEqualTo("제목 B");
    }

    @Test
    void 신규와_기존이_섞여도_각각_반영된다() {
        KopisEvent existing = kopisEvent("KOPIS-0", "갱신된 제목");
        KopisEvent fresh = kopisEvent("KOPIS-NEW", "새 공연");

        int processed = upserter.upsertAll(List.of(existing, fresh));

        assertThat(processed).isEqualTo(2);
        assertThat(eventRepository.findByKopisId("KOPIS-0").orElseThrow().getTitle()).isEqualTo("갱신된 제목");
        assertThat(eventRepository.findByKopisId("KOPIS-NEW")).isPresent();
    }

    private KopisEvent kopisEvent(String kopisId, String title) {
        KopisEvent k = new KopisEvent();
        k.kopisId = kopisId;
        k.title = title;
        k.genre = "연극";
        return k;
    }
}
