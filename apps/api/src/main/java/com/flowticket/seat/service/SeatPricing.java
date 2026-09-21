package com.flowticket.seat.service;

import com.flowticket.seat.domain.SeatGrade;
import com.flowticket.seat.repository.EventSeatPriceRepository;
import com.flowticket.seat.repository.SeatRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 등급별 가격 조회. 좌석맵(조회)과 선점(명령)이 같은 값을 본다.
 *
 * 두 경로가 각자 계산하면 표시 가격과 주문 금액이 갈릴 수 있다. 호출자의 트랜잭션에 그대로 참여한다
 * (선점은 쓰기 트랜잭션 안에서, 좌석맵은 읽기 전용 트랜잭션 안에서 부른다).
 */
@Component
public class SeatPricing {

    private final EventSeatPriceRepository priceRepository;
    private final SeatRepository seatRepository;

    public SeatPricing(EventSeatPriceRepository priceRepository, SeatRepository seatRepository) {
        this.priceRepository = priceRepository;
        this.seatRepository = seatRepository;
    }

    public Map<SeatGrade, Integer> priceMap(Long eventId) {
        return priceRepository.findByEventId(eventId).stream()
                .collect(Collectors.toMap(p -> p.getGrade(), p -> p.getPrice()));
    }

    /** 주문 시점 가격 스냅샷의 합(ADR-004). 등급 가격이 없으면 0으로 센다. */
    public int totalPrice(Long eventId, List<Long> seatIds) {
        Map<SeatGrade, Integer> prices = priceMap(eventId);
        return seatRepository.findAllById(seatIds).stream()
                .mapToInt(s -> prices.getOrDefault(s.getGrade(), 0)).sum();
    }
}
