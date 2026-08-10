package com.flowticket.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.flowticket.event.domain.Event;
import com.flowticket.event.dto.EventDetailResponse;
import com.flowticket.event.kopis.KopisClient;
import com.flowticket.event.kopis.KopisEventDetail;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.event.service.EventService;
import com.flowticket.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * <b>사용자 상세 조회가 외부 API를 호출하지 않는다</b>는 것을 회귀로 고정한다.
 *
 * <p>예전에는 {@code GET /events/{id}}마다 KOPIS 상세를 동기 호출했다. 그 구조에서는 외부
 * 호출량이 우리 트래픽의 함수가 되어, 부하 시 초당 70회가 나가 KOPIS 이용 제한(IP당 1초 10회)을
 * 약 7배 초과했고 400 Request Blocked를 2,014건 맞았다. 응답시간도 외부 지연에 종속됐다
 * (이 경로만 p50 89ms, 다른 조회 18ms).
 *
 * <p>고친 뒤에도 누군가 "상세가 비어 있으니 없으면 그때 불러오자"는 식으로 되돌리기 쉽다.
 * 그건 lazy cache가 되어 트래픽 비례 구간이 다시 생긴다. 그래서 <b>호출이 0회임을 단언</b>한다.
 */
@SpringBootTest
class EventDetailNoExternalCallTest extends IntegrationTestSupport {

    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;

    /** 실제로 불리면 테스트가 깨지도록 mock으로 감시한다. */
    @MockBean KopisClient kopisClient;

    @Test
    void 상세조회는_DB에_채워진_값을_외부호출_없이_반환한다() {
        Event event = eventRepository.save(Event.builder()
                .kopisId("PF900001").title("동기화된 공연").venue("올림픽홀").build());
        // 동기화 배치가 미리 채운 상태를 재현
        event.updateDetail("1시간 30분", "만 12세 이상", "전석 30,000원",
                "가수 김플로우", "여름밤의 콘서트", "매일 19시");
        eventRepository.saveAndFlush(event);

        EventDetailResponse res = eventService.detail(event.getId());

        assertThat(res.priceText()).isEqualTo("전석 30,000원");
        assertThat(res.cast()).isEqualTo("가수 김플로우");
        assertThat(res.synopsis()).isEqualTo("여름밤의 콘서트");
        assertThat(res.schedule()).isEqualTo("매일 19시");
        assertThat(res.runningTime()).isEqualTo("1시간 30분");

        verify(kopisClient, never()).fetchDetail(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 상세가_아직_없어도_외부를_부르지_않고_null로_응답한다() {
        // 상세를 아직 못 받은 공연. 예전이라면 여기서 KOPIS를 불렀다.
        Event event = eventRepository.save(Event.builder()
                .kopisId("PF900002").title("상세 미수집 공연").build());

        EventDetailResponse res = eventService.detail(event.getId());

        assertThat(res.title()).isEqualTo("상세 미수집 공연");
        assertThat(res.priceText()).isNull();
        assertThat(res.cast()).isNull();

        verify(kopisClient, never()).fetchDetail(org.mockito.ArgumentMatchers.anyString());
    }

    /** 이 필드가 응답에 실리는지까지 확인해 매핑 누락을 막는다. */
    @Test
    void 상세필드_매핑이_빠지지_않는다() {
        Event event = eventRepository.save(Event.builder().kopisId("PF900003").title("매핑").build());
        KopisEventDetail d = new KopisEventDetail();
        d.priceText = "P";
        d.cast = "C";
        d.synopsis = "S";
        d.schedule = "D";
        event.updateDetail(null, null, d.priceText, d.cast, d.synopsis, d.schedule);
        eventRepository.saveAndFlush(event);

        EventDetailResponse res = eventService.detail(event.getId());

        assertThat(res.priceText()).isEqualTo("P");
        assertThat(res.cast()).isEqualTo("C");
        assertThat(res.synopsis()).isEqualTo("S");
        assertThat(res.schedule()).isEqualTo("D");
    }
}
