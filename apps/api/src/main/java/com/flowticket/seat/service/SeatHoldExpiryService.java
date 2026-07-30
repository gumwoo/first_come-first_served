package com.flowticket.seat.service;

import com.flowticket.seat.domain.SeatHold;
import com.flowticket.seat.domain.SeatHoldItem;
import com.flowticket.seat.domain.SeatHoldStatus;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.repository.SeatHoldItemRepository;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatRepository;
import com.flowticket.seat.sse.SeatSseRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선점(HOLD) 만료 회수. TTL 지난 홀드를 EXPIRED로 바꾸고 좌석을 AVAILABLE로 복구(재고 반환).
 * 좌석이 풀리면 seat.hold.expired를 이벤트 SSE로 push. 상태 갱신은 벌크 UPDATE로 원자적.
 */
@Slf4j
@Service
public class SeatHoldExpiryService {

    private final SeatHoldRepository holdRepository;
    private final SeatHoldItemRepository holdItemRepository;
    private final SeatRepository seatRepository;
    private final SeatSseRegistry sse;

    public SeatHoldExpiryService(SeatHoldRepository holdRepository, SeatHoldItemRepository holdItemRepository,
                                 SeatRepository seatRepository, SeatSseRegistry sse) {
        this.holdRepository = holdRepository;
        this.holdItemRepository = holdItemRepository;
        this.seatRepository = seatRepository;
        this.sse = sse;
    }

    @Scheduled(fixedRateString = "${seat.sweep-interval-ms:60000}")
    @Transactional
    public void sweepExpired() {
        List<SeatHold> holds = holdRepository.findByStatusAndExpiresAtBefore(
                SeatHoldStatus.HELD, LocalDateTime.now());
        if (holds.isEmpty()) {
            return;
        }
        int expiredCount = 0;
        for (SeatHold h : holds) {
            // 홀드를 조건부로 먼저 EXPIRED 전이. 결제가 경합에서 이겨 CONVERTED면 0행 → 스킵.
            // 실제로 만료된 홀드의 좌석만 해제·알림 → SOLD 좌석에 유령 seat.hold.expired 방지(TS-011 ④).
            if (holdRepository.expireHolds(List.of(h.getId())) != 1) {
                continue;
            }
            List<Long> seatIds = holdItemRepository.findByHoldId(h.getId()).stream()
                    .map(SeatHoldItem::getSeatId).toList();
            seatRepository.releaseSeats(seatIds, SeatStatus.AVAILABLE, SeatStatus.HELD); // HELD 좌석만 복구
            sse.broadcast(h.getEventId(), "seat.hold.expired", Map.of("seatIds", seatIds));
            expiredCount++;
        }
        log.info("[seat] 선점 만료 회수 {}/{}건", expiredCount, holds.size());
    }
}
