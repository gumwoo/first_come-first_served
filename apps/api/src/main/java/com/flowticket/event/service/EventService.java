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

    // KopisClient 의존이 사라졌다 — 사용자 조회 경로에서 외부 호출을 걷어낸 결과다.
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
        // search()가 이미 요약 DTO를 돌려준다(목록에 필요한 컬럼만 SELECT).
        return eventRepository.search(onSale, Pageable.ofSize(POPULAR_SIZE)).stream().toList();
    }

    /**
     * 상세 조회. <b>DB만 읽는다 — 외부 호출이 없다.</b>
     *
     * <p>예전에는 여기서 KOPIS 상세를 lazy 호출했다. 그 구조의 대가가 컸다.
     * <ul>
     *   <li>외부 호출량이 <b>우리 트래픽의 함수</b>가 된다 — 부하 시 초당 70회가 나가 KOPIS
     *       이용 제한(IP당 1초 10회)을 약 7배 초과했고 400 Request Blocked를 2,014건 맞았다</li>
     *   <li>사용자 응답시간이 외부 지연에 직접 종속된다 — 이 경로만 p50 89ms, 다른 조회는 18ms</li>
     *   <li>외부가 느려지면 요청 스레드가 묶인다 — 타임아웃이 없던 시절엔 API 전체가 멎을 수 있었다</li>
     * </ul>
     *
     * <p>이제 동기화 배치가 미리 채운다. 아직 못 받은 공연은 해당 필드가 null인데, 이는 예전에
     * 외부 호출이 실패했을 때 나가던 응답과 같은 모양이라 클라이언트 계약은 그대로다.
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
