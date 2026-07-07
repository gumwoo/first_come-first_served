package com.flowticket.seat.service;

import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.queue.service.QueueService;
import com.flowticket.seat.domain.Seat;
import com.flowticket.seat.domain.SeatGrade;
import com.flowticket.seat.domain.SeatHold;
import com.flowticket.seat.domain.SeatHoldItem;
import com.flowticket.seat.domain.SeatHoldStatus;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.dto.HoldResponse;
import com.flowticket.seat.dto.SeatMapResponse;
import com.flowticket.seat.dto.SeatMapResponse.GradeInfo;
import com.flowticket.seat.dto.SeatMapResponse.SeatInfo;
import com.flowticket.seat.repository.EventSeatPriceRepository;
import com.flowticket.seat.repository.SeatHoldItemRepository;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 좌석 조회/선점/해제. 선점은 조건부 UPDATE로 원자화(초과판매 0, ADR-003). */
@Service
@Transactional(readOnly = true)
public class SeatService {

    private final SeatRepository seatRepository;
    private final EventSeatPriceRepository priceRepository;
    private final SeatHoldRepository holdRepository;
    private final SeatHoldItemRepository holdItemRepository;
    private final QueueService queueService;
    private final long holdTtl;
    private final int maxPerUser;

    public SeatService(SeatRepository seatRepository, EventSeatPriceRepository priceRepository,
                       SeatHoldRepository holdRepository, SeatHoldItemRepository holdItemRepository,
                       QueueService queueService,
                       @Value("${seat.hold-ttl:300}") long holdTtl,
                       @Value("${seat.max-per-user:4}") int maxPerUser) {
        this.seatRepository = seatRepository;
        this.priceRepository = priceRepository;
        this.holdRepository = holdRepository;
        this.holdItemRepository = holdItemRepository;
        this.queueService = queueService;
        this.holdTtl = holdTtl;
        this.maxPerUser = maxPerUser;
    }

    /** 좌석맵: 등급 요약(가격·잔여) + 개별 좌석. */
    public SeatMapResponse getSeats(Long eventId) {
        Map<SeatGrade, Integer> prices = priceMap(eventId);
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

    /** 좌석 선점: 입장 검증 → 1인 한도 → 조건부 UPDATE(원자) → 홀드 기록. */
    @Transactional
    public HoldResponse hold(Long userId, Long eventId, List<Long> seatIds, String queueToken) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (!queueService.isAdmitted(queueToken, eventId)) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_ADMITTED);
        }
        // 1인 구매 한도
        List<Long> activeHolds = holdRepository.findIdsByUser(userId, eventId, SeatHoldStatus.HELD);
        long current = activeHolds.isEmpty() ? 0 : holdItemRepository.countByHoldIdIn(activeHolds);
        if (current + seatIds.size() > maxPerUser) {
            throw new BusinessException(ErrorCode.MAX_PER_USER_EXCEEDED);
        }
        // 원자적 선점 — AVAILABLE인 좌석만 HELD. 요청 수와 다르면 일부 매진 → 롤백.
        int held = seatRepository.holdIfAvailable(seatIds, SeatStatus.HELD, SeatStatus.AVAILABLE);
        if (held != seatIds.size()) {
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }
        SeatHold hold = holdRepository.save(SeatHold.builder()
                .eventId(eventId).userId(userId)
                .expiresAt(LocalDateTime.now().plusSeconds(holdTtl)).build());
        for (Long seatId : seatIds) {
            holdItemRepository.save(SeatHoldItem.builder().holdId(hold.getId()).seatId(seatId).build());
        }
        int total = totalPrice(eventId, seatIds);
        return new HoldResponse(hold.getId(), seatIds, total, hold.getExpiresAt());
    }

    /** 선점 해제(소유자). */
    @Transactional
    public void release(Long holdId, Long userId) {
        SeatHold hold = holdRepository.findById(holdId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!hold.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (hold.getStatus() != SeatHoldStatus.HELD) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        List<Long> seatIds = holdItemRepository.findByHoldId(holdId).stream()
                .map(SeatHoldItem::getSeatId).toList();
        // 홀드 상태를 먼저 확정 반영(saveAndFlush). releaseSeats는 @Modifying(clearAutomatically)라
        // 실행 시 영속성 컨텍스트를 비워, 뒤에서 엔티티를 mutate하면 detached라 저장되지 않기 때문.
        hold.release();
        holdRepository.saveAndFlush(hold);
        seatRepository.releaseSeats(seatIds, SeatStatus.AVAILABLE);
    }

    private Map<SeatGrade, Integer> priceMap(Long eventId) {
        return priceRepository.findByEventId(eventId).stream()
                .collect(Collectors.toMap(p -> p.getGrade(), p -> p.getPrice()));
    }

    private int totalPrice(Long eventId, List<Long> seatIds) {
        Map<SeatGrade, Integer> prices = priceMap(eventId);
        return seatRepository.findAllById(seatIds).stream()
                .mapToInt(s -> prices.getOrDefault(s.getGrade(), 0)).sum();
    }
}
