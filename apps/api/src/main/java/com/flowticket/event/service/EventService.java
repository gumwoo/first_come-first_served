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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EventService {

    private static final int POPULAR_SIZE = 10;

    private final EventRepository eventRepository;
    private final KopisClient kopisClient;
    private final RankingService rankingService;

    public EventService(EventRepository eventRepository, KopisClient kopisClient,
                        RankingService rankingService) {
        this.eventRepository = eventRepository;
        this.kopisClient = kopisClient;
        this.rankingService = rankingService;
    }

    /** 목록(장르/지역/상태/기간 필터 + 페이징). status 문자열은 enum으로 변환. */
    public PageResponse<EventSummaryResponse> list(String genre, String region, String status,
                                                   LocalDate from, LocalDate to, int page, int size) {
        var condition = new EventSearchCondition(null, genre, region, parseStatus(status), from, to);
        return search(condition, PageRequest.of(page, size));
    }

    /** 키워드 + 장르/지역/상태 필터 검색 + 페이징. 검색 실행은 인기검색어로 기록(best-effort). */
    public PageResponse<EventSummaryResponse> searchByKeyword(String keyword, String genre,
                                                              String region, String status,
                                                              int page, int size) {
        rankingService.recordSearch(keyword);
        var condition = new EventSearchCondition(keyword, genre, region, parseStatus(status), null, null);
        return search(condition, PageRequest.of(page, size));
    }

    /** 인기 공연 TOP — 누적 조회수 ZSET 상위. 데이터 없으면 ON_SALE 최신순으로 폴백. */
    public List<EventSummaryResponse> popular() {
        List<EventSummaryResponse> ranked = byIdsOrdered(rankingService.topTotal(POPULAR_SIZE));
        return ranked.isEmpty() ? fallbackLatest() : ranked;
    }

    /** 실시간 랭킹 — 지수감쇠 조회수 ZSET 상위. 데이터 없으면 ON_SALE 최신순으로 폴백. */
    public List<EventSummaryResponse> realtimeRanking() {
        List<EventSummaryResponse> ranked = byIdsOrdered(rankingService.topHot(POPULAR_SIZE));
        return ranked.isEmpty() ? fallbackLatest() : ranked;
    }

    /** ZSET 순서(내림차순)를 유지하며 events를 조회해 요약으로 변환. */
    private List<EventSummaryResponse> byIdsOrdered(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Event> byId = eventRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));
        return ids.stream()
                .map(byId::get)
                .filter(e -> e != null)
                .map(EventSummaryResponse::from)
                .toList();
    }

    /** 랭킹 데이터가 없을 때(초기/유입 전) 보여줄 기본: 판매중 최신순. */
    private List<EventSummaryResponse> fallbackLatest() {
        var onSale = new EventSearchCondition(null, null, null, EventStatus.ON_SALE, null, null);
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
