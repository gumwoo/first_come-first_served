package com.flowticket.event.service;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.dto.EventDetailResponse;
import com.flowticket.event.dto.EventSummaryResponse;
import com.flowticket.event.kopis.KopisClient;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.event.repository.EventSearchCondition;
import com.flowticket.global.common.PageResponse;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EventService {

    private static final int POPULAR_SIZE = 8;

    private final EventRepository eventRepository;
    private final KopisClient kopisClient;

    public EventService(EventRepository eventRepository, KopisClient kopisClient) {
        this.eventRepository = eventRepository;
        this.kopisClient = kopisClient;
    }

    /** 목록(장르/상태/기간 필터 + 페이징). status 문자열은 enum으로 변환. */
    public PageResponse<EventSummaryResponse> list(String genre, String status,
                                                   LocalDate from, LocalDate to, int page, int size) {
        var condition = new EventSearchCondition(null, genre, parseStatus(status), from, to);
        return search(condition, PageRequest.of(page, size));
    }

    /** 키워드 검색 + 페이징. */
    public PageResponse<EventSummaryResponse> searchByKeyword(String keyword, int page, int size) {
        var condition = new EventSearchCondition(keyword, null, null, null, null);
        return search(condition, PageRequest.of(page, size));
    }

    /** 인기 TOP — 조회수 집계 전이라 판매중 우선 최신순 N개. 후속 랭킹 지표로 교체 예정. */
    public List<EventSummaryResponse> popular() {
        var onSale = new EventSearchCondition(null, null, EventStatus.ON_SALE, null, null);
        return eventRepository.search(onSale, Pageable.ofSize(POPULAR_SIZE)).stream()
                .map(EventSummaryResponse::from)
                .toList();
    }

    /**
     * 상세 조회. KOPIS 상세(관람시간/연령/가격 등)는 진입 시 lazy 호출(외부 호출이라
     * 트랜잭션 밖 — NOT_SUPPORTED). KOPIS 실패/미연동 시 DB 기본만 반환.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public EventDetailResponse detail(Long id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (event.getKopisId() == null) {
            return EventDetailResponse.from(event);
        }
        return kopisClient.fetchDetail(event.getKopisId())
                .map(detail -> EventDetailResponse.from(event, detail))
                .orElseGet(() -> EventDetailResponse.from(event));
    }

    private PageResponse<EventSummaryResponse> search(EventSearchCondition condition, Pageable pageable) {
        return PageResponse.from(
                eventRepository.search(condition, pageable).map(EventSummaryResponse::from));
    }

    private EventStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return EventStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
