package com.flowticket.event.kopis;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    /**
     * KOPIS 수집분을 events에 반영한다(kopis_id 기준 멱등).
     *
     * <p>존재 확인은 <b>배치 조회 1회</b>로 끝낸다(IMP-012). 예전엔 항목마다 {@code findByKopisId}를
     * 호출해 N건에 SELECT N번이 나갔다. 또한 KOPIS 응답에 같은 kopisId가 중복으로 들어오면 한 배치에서
     * INSERT가 두 번 나가 UNIQUE 위반이 되므로, <b>배치 내 중복은 먼저 제거</b>한다(뒤 항목이 최신).
     *
     * @return 유효 항목(kopisId·title 보유) 중 신규/갱신으로 처리한 건수 —
     *         "새로 추가된 공연 수"가 아니라 <b>처리한 항목 수</b>다.
     */
    @Transactional
    public int upsertAll(List<KopisEvent> items) {
        // 1) 유효 항목만 남기고 배치 내 kopisId 중복 제거(뒤에 온 값으로 덮음)
        Map<String, KopisEvent> unique = new LinkedHashMap<>();
        for (KopisEvent k : items) {
            if (k.kopisId != null && k.title != null) {
                unique.put(k.kopisId, k);
            }
        }
        if (unique.isEmpty()) {
            return 0;
        }
        // 2) 기존 행을 한 번에 조회 → 메모리에서 신규/기존 분기
        Map<String, Event> existing = eventRepository.findAllByKopisIdIn(unique.keySet()).stream()
                .collect(Collectors.toMap(Event::getKopisId, Function.identity(), (a, b) -> a));

        for (KopisEvent k : unique.values()) {
            EventStatus status = mapStatus(k.state);
            LocalDate start = parseDate(k.startDate);
            LocalDate end = parseDate(k.endDate);

            Event found = existing.get(k.kopisId);
            if (found != null) {
                found.updateFromSync(k.title, k.venue, k.region, k.genre,
                        k.posterUrl, start, end, null, null, status);
            } else {
                eventRepository.save(Event.builder()
                        .kopisId(k.kopisId).title(k.title).venue(k.venue).region(k.region)
                        .genre(k.genre).posterUrl(k.posterUrl).startDate(start).endDate(end)
                        .status(status).build());
            }
        }
        log.info("[kopis] upsert 완료 {}건(수집 {}건, 조회 1회)", unique.size(), items.size());
        return unique.size();
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
