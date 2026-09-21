package com.flowticket.seat.service;

import com.flowticket.seat.domain.Seat;
import com.flowticket.seat.domain.SeatGrade;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.dto.SeatMapResponse;
import com.flowticket.seat.dto.SeatMapResponse.GradeInfo;
import com.flowticket.seat.dto.SeatMapResponse.SeatInfo;
import com.flowticket.seat.repository.SeatRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB에서 좌석맵을 읽는 읽기 전용 협력자.
 *
 * SeatQueryService 안에 두면 캐시 히트 경로에서도 트랜잭션이 열리거나, 자기 프록시를 주입해야 한다.
 * 분리하면 "캐시를 보는 동안에는 커넥션을 쥐지 않는다"가 호출 관계로 드러난다(ADR-019).
 */
@Component
public class SeatMapLoader {

    private final SeatRepository seatRepository;
    private final SeatPricing pricing;

    public SeatMapLoader(SeatRepository seatRepository, SeatPricing pricing) {
        this.seatRepository = seatRepository;
        this.pricing = pricing;
    }

    /** 등급 요약(가격·잔여) + 개별 좌석. */
    @Transactional(readOnly = true)
    public SeatMapResponse load(Long eventId) {
        Map<SeatGrade, Integer> prices = pricing.priceMap(eventId);
        List<Seat> seats = seatRepository.findByEventId(eventId);

        List<GradeInfo> grades = new ArrayList<>();
        for (Map.Entry<SeatGrade, Integer> e : prices.entrySet()) {
            SeatGrade g = e.getKey();
            long total = seats.stream().filter(s -> s.getGrade() == g).count();
            long avail = seats.stream().filter(s -> s.getGrade() == g && s.getStatus() == SeatStatus.AVAILABLE).count();
            grades.add(new GradeInfo(g.name(), e.getValue(), total, avail));
        }
        List<SeatInfo> seatInfos = seats.stream()
                .map(s -> new SeatInfo(s.getId(), s.getGrade().name(), s.getZone(),
                        s.getSeatRow(), s.getSeatCol(), s.getStatus().name()))
                .toList();
        return new SeatMapResponse(eventId, grades, seatInfos);
    }
}
