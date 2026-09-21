package com.flowticket.seat.service;

import com.flowticket.global.error.BusinessException;
import com.flowticket.event.domain.Event;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.queue.service.QueueService;
import com.flowticket.seat.domain.SeatHold;
import com.flowticket.seat.domain.SeatHoldItem;
import com.flowticket.seat.domain.SeatHoldStatus;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.dto.HoldResponse;
import com.flowticket.seat.repository.SeatHoldItemRepository;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatQuotaRepository;
import com.flowticket.seat.repository.SeatRepository;
import com.flowticket.seat.sse.SeatSseRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석 선점·해제(명령). 선점은 조건부 UPDATE로 원자화한다(초과판매 0, ADR-003).
 *
 * 조회는 SeatQueryService가 맡는다. 일관성 모델이 다르기 때문이다 — 조회는 짧은 TTL 캐시로
 * 낡은 값을 허용하고, 선점은 한 좌석도 겹치지 않게 한다(ADR-019 3단계).
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class SeatService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final SeatHoldRepository holdRepository;
    private final SeatHoldItemRepository holdItemRepository;
    private final SeatQuotaRepository quotaRepository;
    private final SeatPricing pricing;
    private final QueueService queueService;
    private final SeatSseRegistry sse;
    private final long holdTtl;
    private final int maxPerUser;
    private final Clock clock;

    public SeatService(EventRepository eventRepository,
                       SeatRepository seatRepository,
                       SeatHoldRepository holdRepository, SeatHoldItemRepository holdItemRepository,
                       SeatQuotaRepository quotaRepository, SeatPricing pricing,
                       QueueService queueService, SeatSseRegistry sse,
                       @Value("${seat.hold-ttl:300}") long holdTtl,
                       @Value("${seat.max-per-user:4}") int maxPerUser,
                       Clock clock) {
        this.clock = clock;
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.holdRepository = holdRepository;
        this.holdItemRepository = holdItemRepository;
        this.quotaRepository = quotaRepository;
        this.pricing = pricing;
        this.queueService = queueService;
        this.sse = sse;
        this.holdTtl = holdTtl;
        this.maxPerUser = maxPerUser;
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
        // 판매 상태 게이트. 대기열이 이미 같은 검사를 하지만 여기서도 본다.
        //   1) 입장 토큰은 admit-ttl(기본 300초) 동안 살아 있어, 그 사이 운영자가 공연을
        //      PAUSED·CLOSED로 바꿔도 이미 발급된 토큰으로 계속 선점할 수 있다.
        //   2) 대기열을 통과한 토큰만 여기 오지만, 그 토큰이 발급된 시점의 상태와
        //      지금 상태는 다를 수 있다. 검사 시점이 다르면 다른 검사다.
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!event.getStatus().isBookable()) {
            throw new BusinessException(ErrorCode.EVENT_NOT_ON_SALE);
        }
        // 좌석이 이 이벤트 소속인지 검증(다른 이벤트 좌석 id 혼입 차단). 원자 UPDATE에도 eventId 가드를 둔다.
        if (seatRepository.countByIdInAndEventId(seatIds, eventId) != seatIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        // 1인 구매 한도: 좌석 초과판매와 달리 집계 규칙이라 조건부 UPDATE로 원자화할
        // 대상 행이 없다. 읽기→검사→행위 사이에 다른 요청이 끼어들면 둘 다 통과하므로
        // (사용자, 공연) 단위로 직렬화한 뒤 단일 SQL로 센다. 근거는 SeatQuotaRepository 참조.
        // 좌석 총량을 늘리는 진입점이 이 메서드 하나라 여기만 잠그면 충분하다.
        quotaRepository.acquireQuotaLock(quotaLockKey(userId), quotaLockKey(eventId));
        long current = quotaRepository.countActiveSeats(userId, eventId);
        if (current + seatIds.size() > maxPerUser) {
            throw new BusinessException(ErrorCode.MAX_PER_USER_EXCEEDED);
        }
        // 원자적 선점: AVAILABLE인 좌석만 HELD. 요청 수와 다르면 일부가 이미 선점된 것이라 롤백한다.
        int held = seatRepository.holdIfAvailable(seatIds, eventId, SeatStatus.HELD, SeatStatus.AVAILABLE);
        if (held != seatIds.size()) {
            throw new BusinessException(soldOutOrConflict(eventId, held));
        }
        SeatHold hold = holdRepository.save(SeatHold.builder()
                .eventId(eventId).userId(userId)
                .expiresAt(LocalDateTime.now(clock).plusSeconds(holdTtl)).build());
        for (Long seatId : seatIds) {
            holdItemRepository.save(SeatHoldItem.builder().holdId(hold.getId()).seatId(seatId).build());
        }
        int total = pricing.totalPrice(eventId, seatIds);
        sse.broadcast(eventId, "seat.held", Map.of("seatIds", seatIds)); // 실시간 좌석맵 반영
        return new HoldResponse(hold.getId(), seatIds, total, hold.getExpiresAt());
    }

    /**
     * 선점 실패의 원인을 가른다: 공연 매진(SOLD_OUT)인지, 고른 좌석만 뺏긴 것(SEAT_CONFLICT)인지.
     * SOLD_OUT은 "잔여 0"일 때만 쓴다(docs/rules/domain/seat.md).
     *
     * 롤백 전에 불리므로 방금 HELD로 바꾼 held석을 잔여에 더한다. 더하지 않으면 요청 좌석을 다 잡고
     * 다른 이유로 실패한 경우에 잔여를 0으로 읽어 매진이라고 답한다.
     */
    private ErrorCode soldOutOrConflict(Long eventId, int held) {
        long remaining = seatRepository.countByEventIdAndStatus(eventId, SeatStatus.AVAILABLE) + held;
        return remaining == 0 ? ErrorCode.SOLD_OUT : ErrorCode.SEAT_CONFLICT;
    }

    /**
     * advisory lock 키로 쓸 int 변환. pg_advisory_xact_lock의 2인자형이 int4라
     * bigint 하나에 해시로 밀어 넣는 방식보다 안전하다(해시는 무관한 쌍끼리 서로 막을 수 있다).
     * 범위를 넘으면 값이 잘리는 대신 ArithmeticException으로 즉시 실패한다.
     * 키가 겹쳐 생기는 불필요한 대기는 정합성 오류로 드러나지 않아 찾기 어렵다.
     */
    private static int quotaLockKey(Long id) {
        return Math.toIntExact(id);
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
        seatRepository.releaseSeats(seatIds, SeatStatus.AVAILABLE, SeatStatus.HELD); // 수동 해제: HELD→AVAILABLE
        sse.broadcast(hold.getEventId(), "seat.hold.released", Map.of("seatIds", seatIds)); // 재고 복구 반영
    }

}
