package com.flowticket.event.service;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.dto.EventDetailResponse;
import com.flowticket.event.dto.EventSummaryResponse;
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
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EventService {

    private static final int POPULAR_SIZE = 10;

    private final EventRepository eventRepository;
    private final RankingService rankingService;

    // KopisClient 의존이 사라졌다. 사용자 조회 경로에서 외부 호출을 걷어낸 결과다.
    public EventService(EventRepository eventRepository, RankingService rankingService) {
        this.eventRepository = eventRepository;
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

    /** 인기 공연 TOP: 누적 조회수 ZSET 상위. 데이터 없으면 ON_SALE 최신순으로 폴백. */
    public List<EventSummaryResponse> popular() {
        List<EventSummaryResponse> ranked = byIdsOrdered(rankingService.topTotal(POPULAR_SIZE));
        return ranked.isEmpty() ? fallbackLatest() : ranked;
    }

    /** 실시간 랭킹: 지수감쇠 조회수 ZSET 상위. 데이터 없으면 ON_SALE 최신순으로 폴백. */
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
        // search()가 이미 요약 DTO를 돌려준다(목록에 필요한 컬럼만 SELECT).
        return eventRepository.search(onSale, Pageable.ofSize(POPULAR_SIZE)).stream().toList();
    }

    /**
     * 상세 조회. DB만 읽는다. 외부 호출이 없다.
     *
     * <p>KOPIS 상세는 동기화 배치가 미리 채운다({@link com.flowticket.event.kopis.KopisDetailSyncer}).
     * 요청마다 부르면 외부 호출량이 트래픽에 비례하고 응답시간이 외부 지연에 묶인다.
     * 아직 못 받은 공연은 해당 필드가 null이다.
     */
    @Transactional(readOnly = true)
    public EventDetailResponse detail(Long id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return EventDetailResponse.from(event);
    }

    private PageResponse<EventSummaryResponse> search(EventSearchCondition condition, Pageable pageable) {
        return PageResponse.from(eventRepository.search(condition, pageable));
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
