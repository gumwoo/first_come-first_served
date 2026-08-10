package com.flowticket.event.repository;

import static com.flowticket.event.domain.QEvent.event;

import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.dto.EventSummaryResponse;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;

public class EventRepositoryImpl implements EventRepositoryCustom {

    private final JPAQueryFactory query;

    public EventRepositoryImpl(JPAQueryFactory query) {
        this.query = query;
    }

    /**
     * ⚠️ {@code selectFrom(event)}로 되돌리지 말 것 — 필요한 컬럼만 고른다.
     *
     * <p>목록이 쓰는 컬럼은 9개인데 엔티티를 통째로 읽으면 20개를 가져온다. V16에서 KOPIS 상세
     * 필드(TEXT 4개: priceText·castInfo·synopsis·scheduleText)가 붙으면서 폭이 더 넓어졌다.
     *
     * <p>다만 이것이 <b>측정된 지연 증가의 원인이라는 근거는 없다</b> — EXPLAIN으로 재보니 쿼리
     * 형태별 차이가 0.1~0.2ms에 그쳤고 전부 캐시 히트였다. 쓰지 않는 데이터를 읽지 않는다는
     * 이유만으로 충분한 변경이다.
     */
    @Override
    public Page<EventSummaryResponse> search(EventSearchCondition c, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder()
                .and(keywordContains(c.keyword()))
                .and(genreEq(c.genre()))
                .and(regionContains(c.region()))
                .and(statusEq(c.status()))
                .and(startFrom(c.from()))
                .and(startTo(c.to()));

        // Tuple로 받아 매핑한다. Projections.constructor + status.stringValue()를 쓰면 SQL에
        // cast가 끼는데, 여기서는 그럴 이유가 없다(enum은 그대로 받아 name()으로 바꾼다).
        List<EventSummaryResponse> content = query
                .select(event.id, event.title, event.venue, event.region, event.genre,
                        event.posterUrl, event.startDate, event.status, event.basePrice)
                .from(event)
                .where(where)
                .orderBy(event.startDate.asc().nullsLast(), event.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch()
                .stream()
                .map(t -> new EventSummaryResponse(
                        t.get(event.id), t.get(event.title), t.get(event.venue), t.get(event.region),
                        t.get(event.genre), t.get(event.posterUrl), t.get(event.startDate),
                        statusName(t.get(event.status)), t.get(event.basePrice)))
                .toList();

        Long total = query.select(event.count()).from(event).where(where).fetchOne();
        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }

    private static String statusName(EventStatus status) {
        return status != null ? status.name() : null;
    }

    private BooleanExpression keywordContains(String keyword) {
        return StringUtils.hasText(keyword) ? event.title.containsIgnoreCase(keyword) : null;
    }

    private BooleanExpression genreEq(String genre) {
        return StringUtils.hasText(genre) ? event.genre.eq(genre) : null;
    }

    private BooleanExpression regionContains(String region) {
        // 시도 전체명("서울특별시")에 짧은 라벨("서울")이 매칭되도록 contains
        return StringUtils.hasText(region) ? event.region.containsIgnoreCase(region) : null;
    }

    private BooleanExpression statusEq(EventStatus status) {
        return status != null ? event.status.eq(status) : null;
    }

    private BooleanExpression startFrom(LocalDate from) {
        return from != null ? event.startDate.goe(from) : null;
    }

    private BooleanExpression startTo(LocalDate to) {
        return to != null ? event.startDate.loe(to) : null;
    }
}
