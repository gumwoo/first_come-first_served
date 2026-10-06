package com.flowticket.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.support.MutableClock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 진입 경로 판매 상태 캐시: TTL 안에서는 DB를 다시 읽지 않고, 0이면 매번 읽는다. */
class BookableEventCacheTest {

    private final EventRepository repo = mock(EventRepository.class);
    private final MutableClock clock = new MutableClock();

    private Event eventWith(EventStatus status) {
        Event e = mock(Event.class);
        when(e.getStatus()).thenReturn(status);
        return e;
    }

    @Test
    void TTL_0이면_매번_DB를_읽는다_지금까지와_같다() {
        when(repo.findById(1L)).thenReturn(Optional.of(eventWith(EventStatus.ON_SALE)));
        BookableEventCache cache = new BookableEventCache(repo, clock, 0);

        cache.status(1L);
        cache.status(1L);

        verify(repo, times(2)).findById(1L);
    }

    @Test
    void TTL_안에서는_DB를_다시_읽지_않고_지나면_다시_읽는다() {
        Event e = eventWith(EventStatus.ON_SALE);
        when(repo.findById(1L)).thenReturn(Optional.of(e));
        BookableEventCache cache = new BookableEventCache(repo, clock, 1000);

        assertThat(cache.status(1L)).isEqualTo(EventStatus.ON_SALE);
        clock.advance(Duration.ofMillis(999));
        when(e.getStatus()).thenReturn(EventStatus.PAUSED);
        assertThat(cache.status(1L)).isEqualTo(EventStatus.ON_SALE); // TTL 안: 이전 상태(설계상 감수)
        verify(repo, times(1)).findById(1L);

        clock.advance(Duration.ofMillis(1));
        assertThat(cache.status(1L)).isEqualTo(EventStatus.PAUSED); // TTL 지남: 다시 읽어 바뀐 상태
        verify(repo, times(2)).findById(1L);
    }

    @Test
    void 없는_공연은_기억하지_않는다() {
        when(repo.findById(9L)).thenReturn(Optional.empty());
        BookableEventCache cache = new BookableEventCache(repo, clock, 1000);

        assertThatThrownBy(() -> cache.status(9L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> cache.status(9L)).isInstanceOf(BusinessException.class);

        verify(repo, times(2)).findById(9L);
    }
}
