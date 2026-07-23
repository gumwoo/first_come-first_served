package com.flowticket.admin.service;

import com.flowticket.admin.dto.AdminEventDetail;
import com.flowticket.admin.dto.AdminEventSummary;
import com.flowticket.admin.dto.CreateEventRequest;
import com.flowticket.admin.dto.UpdateEventRequest;
import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.common.PageResponse;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 운영 이벤트 관리(S07). 수동 등록·조회·부분 수정. KOPIS 동기화 경로와 분리. */
@Service
public class AdminEventService {

    private final EventRepository eventRepository;

    public AdminEventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminEventSummary> list(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return PageResponse.from(eventRepository.findAllByOrderByIdDesc(pageable).map(AdminEventSummary::from));
    }

    @Transactional(readOnly = true)
    public AdminEventDetail detail(Long id) {
        return AdminEventDetail.from(find(id));
    }

    @Transactional
    public AdminEventDetail create(CreateEventRequest req) {
        Event event = Event.builder()
                .title(req.title())
                .venue(req.venue())
                .region(req.region())
                .genre(req.genre())
                .posterUrl(req.posterUrl())
                .startDate(req.startDate())
                .endDate(req.endDate())
                .runningTime(req.runningTime())
                .ageLimit(req.ageLimit())
                .status(parseStatus(req.status())) // null이면 엔티티 기본값(SCHEDULED)
                .basePrice(req.basePrice())
                .build();
        return AdminEventDetail.from(eventRepository.save(event));
    }

    @Transactional
    public AdminEventDetail update(Long id, UpdateEventRequest req) {
        Event event = find(id);
        event.edit(req.title(), req.venue(), req.region(), req.genre(), req.posterUrl(),
                req.startDate(), req.endDate(), req.runningTime(), req.ageLimit(),
                parseStatus(req.status()), req.basePrice());
        return AdminEventDetail.from(event); // 영속 상태 → 트랜잭션 커밋 시 flush
    }

    private Event find(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private EventStatus parseStatus(String status) {
        if (!StringUtils.hasText(status)) return null;
        try {
            return EventStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
