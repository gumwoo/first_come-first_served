package com.flowticket.event.repository;

import static com.flowticket.event.domain.QEvent.event;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
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

    @Override
    public Page<Event> search(EventSearchCondition c, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder()
                .and(keywordContains(c.keyword()))
                .and(genreEq(c.genre()))
                .and(regionContains(c.region()))
                .and(statusEq(c.status()))
                .and(startFrom(c.from()))
                .and(startTo(c.to()));

        List<Event> content = query.selectFrom(event)
                .where(where)
                .orderBy(event.startDate.asc().nullsLast(), event.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = query.select(event.count()).from(event).where(where).fetchOne();
        return new PageImpl<>(content, pageable, total != null ? total : 0L);
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
