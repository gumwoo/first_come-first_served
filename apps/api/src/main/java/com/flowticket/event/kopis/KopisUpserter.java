package com.flowticket.event.kopis;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** KOPIS 항목을 events에 upsert(kopis_id 기준). DB 전용(외부 호출 없음)이라 트랜잭션 안전. */
@Slf4j
@Component
public class KopisUpserter {

    private static final DateTimeFormatter KOPIS_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final EventRepository eventRepository;

    public KopisUpserter(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Transactional
    public int upsertAll(List<KopisEvent> items) {
        int count = 0;
        for (KopisEvent k : items) {
            if (k.kopisId == null || k.title == null) {
                continue;
            }
            EventStatus status = mapStatus(k.state);
            LocalDate start = parseDate(k.startDate);
            LocalDate end = parseDate(k.endDate);

            eventRepository.findByKopisId(k.kopisId).ifPresentOrElse(
                    existing -> existing.updateFromSync(k.title, k.venue, k.region, k.genre,
                            k.posterUrl, start, end, null, null, status),
                    () -> eventRepository.save(Event.builder()
                            .kopisId(k.kopisId).title(k.title).venue(k.venue).region(k.region)
                            .genre(k.genre).posterUrl(k.posterUrl).startDate(start).endDate(end)
                            .status(status).build()));
            count++;
        }
        log.info("[kopis] upsert 완료 {}건", count);
        return count;
    }

    private EventStatus mapStatus(String state) {
        if (state == null) return EventStatus.SCHEDULED;
        return switch (state) {
            case "공연중" -> EventStatus.ON_SALE;
            case "공연완료" -> EventStatus.CLOSED;
            default -> EventStatus.SCHEDULED; // 공연예정 등
        };
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim(), KOPIS_DATE);
        } catch (Exception e) {
            return null;
        }
    }
}
