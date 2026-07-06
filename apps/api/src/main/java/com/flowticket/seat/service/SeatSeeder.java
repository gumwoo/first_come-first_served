package com.flowticket.seat.service;

import com.flowticket.event.domain.Event;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.seat.domain.EventSeatPrice;
import com.flowticket.seat.domain.Seat;
import com.flowticket.seat.domain.SeatGrade;
import com.flowticket.seat.repository.EventSeatPriceRepository;
import com.flowticket.seat.repository.SeatRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석·가격 기본 시딩(멱등). KOPIS엔 좌석·가격이 없어 기본 템플릿으로 생성한다.
 * 가격은 장르 티어(ADR-004), 좌석은 VIP10/R20/S30/A40. base_price는 등급 최저가로 기록.
 */
@Service
public class SeatSeeder {

    private static final SeatGrade[] GRADES = {SeatGrade.VIP, SeatGrade.R, SeatGrade.S, SeatGrade.A};
    private static final int[] COUNTS = {10, 20, 30, 40};

    private final SeatRepository seatRepository;
    private final EventSeatPriceRepository priceRepository;
    private final EventRepository eventRepository;

    public SeatSeeder(SeatRepository seatRepository, EventSeatPriceRepository priceRepository,
                      EventRepository eventRepository) {
        this.seatRepository = seatRepository;
        this.priceRepository = priceRepository;
        this.eventRepository = eventRepository;
    }

    /** 관리자/수동 트리거: eventId로 조회 후 시딩. */
    @Transactional
    public void seedForEvent(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        seedIfAbsent(event);
    }

    /** 좌석 없으면 기본 좌석맵·가격 생성(멱등). 최종 방어선은 unique 제약. */
    @Transactional
    public void seedIfAbsent(Event event) {
        Long eventId = event.getId();
        if (seatRepository.existsByEventId(eventId)) {
            return; // 빠른 경로(이미 있음)
        }
        PriceTier tier = PriceTier.of(event.getGenre(), eventId);

        for (SeatGrade g : SeatGrade.values()) {
            priceRepository.save(EventSeatPrice.builder()
                    .eventId(eventId).grade(g).price(tier.priceOf(g)).build());
        }

        List<Seat> seats = new ArrayList<>();
        for (int gi = 0; gi < GRADES.length; gi++) {
            SeatGrade g = GRADES[gi];
            for (int i = 1; i <= COUNTS[gi]; i++) {
                seats.add(Seat.builder()
                        .eventId(eventId).grade(g).zone(g.name()).seatRow(g.name()).seatCol(i).build());
            }
        }
        seatRepository.saveAll(seats);

        event.applyBasePrice(tier.priceOf(SeatGrade.A)); // 목록 표시용 최저가
        eventRepository.save(event);
    }
}
