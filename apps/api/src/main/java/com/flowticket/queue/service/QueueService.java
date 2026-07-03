package com.flowticket.queue.service;

import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.queue.domain.QueueStatus;
import com.flowticket.queue.dto.QueueStatusResponse;
import com.flowticket.queue.dto.QueueTokenResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 대기 진입/상태. 1인 1이벤트 1토큰(멱등). 순서는 Redis ZSet(score=진입 seq). */
@Service
public class QueueService {

    private final StringRedisTemplate redis;
    private final int capacity;
    private final long tokenTtl;
    private final long admitIntervalMs;

    public QueueService(StringRedisTemplate redis,
                        @Value("${queue.capacity:100}") int capacity,
                        @Value("${queue.token-ttl:1800}") long tokenTtl,
                        @Value("${queue.admit-interval-ms:1500}") long admitIntervalMs) {
        this.redis = redis;
        this.capacity = capacity;
        this.tokenTtl = tokenTtl;
        this.admitIntervalMs = admitIntervalMs;
    }

    /**
     * 대기 진입. 이미 활성 토큰이 있으면 그 토큰을 반환(1인1토큰).
     * user 키를 SET NX로 원자 예약해, 같은 유저 동시 요청(더블클릭)에도 토큰이 하나만 생기게 한다.
     */
    public QueueTokenResponse issue(Long userId, Long eventId) {
        String userKey = QueueKeys.user(eventId, userId);
        String token = UUID.randomUUID().toString();
        Boolean reserved = redis.opsForValue()
                .setIfAbsent(userKey, token, Duration.ofSeconds(tokenTtl)); // SET NX
        if (!Boolean.TRUE.equals(reserved)) {
            String existing = redis.opsForValue().get(userKey);
            if (existing != null) {
                return currentOrWaiting(existing, eventId); // 기존 토큰(1인1토큰)
            }
            // 극히 드문 경합(예약 확인~조회 사이 만료): 이 요청이 소유권을 가져간다
            redis.opsForValue().set(userKey, token, Duration.ofSeconds(tokenTtl));
        }
        Long seq = redis.opsForValue().increment(QueueKeys.seq(eventId)); // 진입 순번
        redis.opsForZSet().add(QueueKeys.wait(eventId), token, seq == null ? 0 : seq);
        redis.opsForHash().putAll(QueueKeys.token(token),
                Map.of("userId", String.valueOf(userId), "eventId", String.valueOf(eventId)));
        redis.expire(QueueKeys.token(token), Duration.ofSeconds(tokenTtl));
        redis.opsForSet().add(QueueKeys.ACTIVE_EVENTS, String.valueOf(eventId));
        return tokenResponse(token, eventId);
    }

    /** 대기열 이탈: 대기/입장 상태에 따라 슬롯·순번을 정리한다(나가기 실동작). */
    public void leave(String token) {
        Map<Object, Object> meta = redis.opsForHash().entries(QueueKeys.token(token));
        if (meta.isEmpty()) {
            return; // 이미 정리됨
        }
        Long eventId = Long.valueOf((String) meta.get("eventId"));
        Long userId = Long.valueOf((String) meta.get("userId"));
        if (Boolean.TRUE.equals(redis.hasKey(QueueKeys.admit(token)))) {
            redis.delete(QueueKeys.admit(token));                     // 입장 슬롯 반환
            redis.opsForZSet().remove(QueueKeys.admitExp(eventId), token);
            redis.opsForValue().decrement(QueueKeys.admitCount(eventId));
        } else {
            redis.opsForZSet().remove(QueueKeys.wait(eventId), token); // 대기열에서 제거
        }
        redis.delete(QueueKeys.token(token));
        redis.delete(QueueKeys.user(eventId, userId));
    }

    /** 소유자가 아직 대기열 등록 전(경합)이면 EXPIRED로 보일 수 있어 WAITING으로 낙관 처리. */
    private QueueTokenResponse currentOrWaiting(String token, Long eventId) {
        QueueStatus st = statusOf(token, eventId);
        if (st == QueueStatus.EXPIRED) {
            return new QueueTokenResponse(token, QueueStatus.WAITING.name(), rankOf(token, eventId), card(eventId));
        }
        return tokenResponse(token, eventId);
    }

    /** 상태 폴링. 토큰 메타가 사라졌으면(수명 만료) QUEUE_EXPIRED. */
    public QueueStatusResponse status(String token) {
        Map<Object, Object> meta = redis.opsForHash().entries(QueueKeys.token(token));
        if (meta.isEmpty()) {
            throw new BusinessException(ErrorCode.QUEUE_EXPIRED);
        }
        Long eventId = Long.valueOf((String) meta.get("eventId"));
        QueueStatus st = statusOf(token, eventId);
        long total = card(eventId);
        long rank = st == QueueStatus.WAITING ? rankOf(token, eventId) : 0;
        long eta = st == QueueStatus.WAITING ? etaSeconds(rank) : 0;
        return new QueueStatusResponse(rank, total, eta, st.name());
    }

    private QueueTokenResponse tokenResponse(String token, Long eventId) {
        QueueStatus st = statusOf(token, eventId);
        long total = card(eventId);
        long rank = st == QueueStatus.WAITING ? rankOf(token, eventId) : 0;
        return new QueueTokenResponse(token, st.name(), rank, total);
    }

    private QueueStatus statusOf(String token, Long eventId) {
        if (Boolean.TRUE.equals(redis.hasKey(QueueKeys.admit(token)))) {
            return QueueStatus.ADMITTED;
        }
        Long r = redis.opsForZSet().rank(QueueKeys.wait(eventId), token);
        return r != null ? QueueStatus.WAITING : QueueStatus.EXPIRED;
    }

    private long rankOf(String token, Long eventId) {
        Long r = redis.opsForZSet().rank(QueueKeys.wait(eventId), token);
        return r == null ? 0 : r + 1; // 1-based
    }

    private long card(Long eventId) {
        Long c = redis.opsForZSet().zCard(QueueKeys.wait(eventId));
        return c == null ? 0 : c;
    }

    /** 내 앞 대기를 정원 단위로 처리하는 데 걸리는 추정 시간. */
    private long etaSeconds(long rank) {
        long batches = (long) Math.ceil((double) rank / Math.max(capacity, 1));
        return batches * (admitIntervalMs / 1000);
    }
}
