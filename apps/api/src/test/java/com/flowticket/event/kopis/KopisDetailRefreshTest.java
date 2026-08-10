package com.flowticket.event.kopis;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 상세 동기화 <b>대상 선정</b> 규칙을 고정한다.
 *
 * <p>초안은 {@code detailSyncedAt IS NULL}만 대상으로 삼아, 한 번 채운 공연은 <b>영원히 다시
 * 보지 않았다.</b> KOPIS에서 가격·출연진·공연시간이 바뀌어도 우리 값은 그대로 남는다. 문서에는
 * "최대 하루 낡는다"고 적혀 있었지만 실제로는 무한히 낡는 구조였다.
 *
 * <p>규칙은 둘이다 — <b>미수집(NULL)과 오래된 것을 함께</b> 고르고, <b>오래된 순(NULL 먼저)</b>으로
 * 정렬한다. 회차당 상한이 있으므로 정렬이 곧 우선순위다.
 */
@SpringBootTest
class KopisDetailRefreshTest extends IntegrationTestSupport {

    @Autowired EventRepository eventRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        eventRepository.deleteAll();
    }

    /**
     * 과거 시각의 상세 동기화 상태를 만든다.
     *
     * <p>{@code updateDetail()}은 항상 {@code now()}를 찍으므로 도메인 API로는 과거를 만들 수 없다.
     * 그렇다고 운영 저장소에 테스트 전용 메서드를 넣지는 않는다 — 그 자리에서 직접 UPDATE 한다.
     */
    private Event saved(String kopisId, LocalDateTime detailSyncedAt) {
        Event e = eventRepository.saveAndFlush(
                Event.builder().kopisId(kopisId).title(kopisId).build());
        if (detailSyncedAt != null) {
            jdbcTemplate.update("update events set detail_synced_at = ? where id = ?",
                    Timestamp.valueOf(detailSyncedAt), e.getId());
        }
        return e;
    }

    @Test
    void 미수집과_오래된것을_함께_고르고_신선한것은_제외한다() {
        LocalDateTime now = LocalDateTime.now();
        Event never = saved("PF-NULL", null);                    // 미수집
        Event stale = saved("PF-STALE", now.minusDays(30));      // 오래됨
        Event fresh = saved("PF-FRESH", now.minusDays(1));       // 신선

        List<Long> ids = eventRepository.findIdsNeedingDetail(now.minusDays(7), PageRequest.ofSize(10));

        assertThat(ids)
                .as("미수집과 오래된 것만 대상. 신선한 것은 제외")
                .containsExactlyInAnyOrder(never.getId(), stale.getId())
                .doesNotContain(fresh.getId());
    }

    @Test
    void 미수집이_가장_먼저고_그다음은_오래된_순이다() {
        LocalDateTime now = LocalDateTime.now();
        Event older = saved("PF-OLDER", now.minusDays(40));
        Event never = saved("PF-NULL", null);
        Event newer = saved("PF-NEWER", now.minusDays(20));

        List<Long> ids = eventRepository.findIdsNeedingDetail(now.minusDays(7), PageRequest.ofSize(10));

        assertThat(ids)
                .as("NULL 먼저, 그다음 오래된 순 — 회차당 상한이 있으므로 정렬이 곧 우선순위다")
                .containsExactly(never.getId(), older.getId(), newer.getId());
    }

    /**
     * 목록 동기화가 상세 동기화의 결과를 지우면 안 된다.
     *
     * <p>초안은 {@code updateFromSync(..., null, null, status)}로 {@code runningTime}·
     * {@code ageLimit}에 null을 넘겼고 그 메서드가 무조건 덮어썼다. 그래서 상세가 채운 값을
     * <b>다음날 목록 동기화가 지웠고</b>, {@code detailSyncedAt}은 그대로라 갱신 주기(7일)가
     * 지나기 전엔 다시 채워지지도 않았다.
     *
     * <p>이 결함은 원래 있었지만 보이지 않았다 — 예전에는 상세를 요청마다 외부에서 받아
     * DB 값을 덮어 썼기 때문이다. DB에서 읽기 시작하면서 드러났다.
     */
    @Test
    void 목록_재동기화가_상세필드를_지우지_않는다() {
        Event e = eventRepository.saveAndFlush(
                Event.builder().kopisId("PF-KEEP").title("원래 제목").build());
        // 상세 동기화가 채운 상태
        e.updateDetail("120분", "만 12세 이상", "전석 30,000원", "출연진", "줄거리", "매일 19시");
        eventRepository.saveAndFlush(e);

        // 다음날 목록 동기화 — 목록에는 runningTime·ageLimit이 없다
        e.updateFromSync("바뀐 제목", "올림픽홀", "서울특별시", "대중음악",
                "http://p.jpg", LocalDate.now(), LocalDate.now().plusDays(1), EventStatus.ON_SALE);
        eventRepository.saveAndFlush(e);

        Event reloaded = eventRepository.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).as("목록 필드는 갱신된다").isEqualTo("바뀐 제목");
        assertThat(reloaded.getRunningTime()).as("상세가 채운 값이 살아 있어야 한다").isEqualTo("120분");
        assertThat(reloaded.getAgeLimit()).isEqualTo("만 12세 이상");
        assertThat(reloaded.getPriceText()).isEqualTo("전석 30,000원");
        assertThat(reloaded.getCastInfo()).isEqualTo("출연진");
        assertThat(reloaded.getSynopsis()).isEqualTo("줄거리");
        assertThat(reloaded.getScheduleText()).isEqualTo("매일 19시");
    }

    @Test
    void 회차당_상한만큼만_가져온다() {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 5; i++) {
            saved("PF-" + i, now.minusDays(30 + i));
        }

        List<Long> ids = eventRepository.findIdsNeedingDetail(now.minusDays(7), PageRequest.ofSize(2));

        assertThat(ids).hasSize(2);
    }
}
