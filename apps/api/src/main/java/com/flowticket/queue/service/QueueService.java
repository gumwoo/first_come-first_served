package com.flowticket.queue.service;

import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.event.domain.Event;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.queue.domain.QueueStatus;
import com.flowticket.queue.dto.QueueStatusResponse;
import com.flowticket.queue.dto.QueueTokenResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** 대기 진입/상태. 1인 1이벤트 1토큰(멱등). 순서는 Redis ZSet(score=진입 seq). */
@Service
public class QueueService {

    // 입장 슬롯 반환 원자화: admitExp에서 실제로 제거한 요청만 카운트 감소(중복 DECR 방지).
    // 동시 leave / leave↔만료 sweep이 같은 토큰을 이중 차감하는 것을 막는다.
    private static final String LEAVE_ADMIT_LUA = """
            if redis.call('ZREM', KEYS[1], ARGV[1]) == 1 then
              redis.call('DECR', KEYS[2])
              redis.call('DEL', KEYS[3])
              return 1
            end
            return 0
            """;
    private static final DefaultRedisScript<Long> LEAVE_ADMIT_SCRIPT =
            new DefaultRedisScript<>(LEAVE_ADMIT_LUA, Long.class);

    // 토큰 발급 원자화: 유저키 예약(SET NX)과 대기열 등록(순번·ZSet·메타·TTL·활성이벤트)을 한 번에 실행한다.
    // 예전엔 예약과 등록이 여러 왕복으로 나뉘어, 중간에 Redis 장애가 나면 "유저키는 있는데 대기 ZSet엔
    // 없는" 부분 상태가 남을 수 있었다(승격되지 않는 유령 토큰). 예약 실패(이미 토큰 보유)면 0을 반환한다.
    // KEYS: userKey, seqKey, waitKey, tokenKey, activeEvents / ARGV: token, ttl, userId, eventId
    private static final String ISSUE_LUA = """
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2]) then
              local seq = redis.call('INCR', KEYS[2])
              redis.call('ZADD', KEYS[3], seq, ARGV[1])
              redis.call('HSET', KEYS[4], 'userId', ARGV[3], 'eventId', ARGV[4])
              redis.call('EXPIRE', KEYS[4], ARGV[2])
              redis.call('SADD', KEYS[5], ARGV[4])
              return 1
            end
            return 0
            """;
    private static final DefaultRedisScript<Long> ISSUE_SCRIPT =
            new DefaultRedisScript<>(ISSUE_LUA, Long.class);

    // 죽은 토큰 회수 후 재발급(소유권 이전). 예약을 강제로 덮어쓰는 것만 다르고 등록 절차는 동일하며,
    // 옛 토큰 메타 정리(KEYS[6])까지 같은 원자 단위에 넣어 중간 상태를 남기지 않는다.
    private static final String TAKEOVER_LUA = """
            redis.call('DEL', KEYS[6])
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            local seq = redis.call('INCR', KEYS[2])
            redis.call('ZADD', KEYS[3], seq, ARGV[1])
            redis.call('HSET', KEYS[4], 'userId', ARGV[3], 'eventId', ARGV[4])
            redis.call('EXPIRE', KEYS[4], ARGV[2])
            redis.call('SADD', KEYS[5], ARGV[4])
            return 1
            """;
    private static final DefaultRedisScript<Long> TAKEOVER_SCRIPT =
            new DefaultRedisScript<>(TAKEOVER_LUA, Long.class);

    private final StringRedisTemplate redis;
    private final EventRepository eventRepository;
    private final int capacity;
    private final long tokenTtl;
    private final long admitIntervalMs;

    public QueueService(StringRedisTemplate redis, EventRepository eventRepository,
                        @Value("${queue.capacity:100}") int capacity,
                        @Value("${queue.token-ttl:1800}") long tokenTtl,
                        @Value("${queue.admit-interval-ms:1500}") long admitIntervalMs) {
        this.redis = redis;
        this.eventRepository = eventRepository;
        this.capacity = capacity;
        this.tokenTtl = tokenTtl;
        this.admitIntervalMs = admitIntervalMs;
    }

    /**
     * 대기 진입. 이미 활성 토큰이 있으면 그 토큰을 반환(1인1토큰).
     * user 키를 SET NX로 원자 예약해, 같은 유저 동시 요청(더블클릭)에도 토큰이 하나만 생기게 한다.
     */
    public QueueTokenResponse issue(Long userId, Long eventId) {
        requireBookable(eventId);
        String userKey = QueueKeys.user(eventId, userId);
        String token = UUID.randomUUID().toString();

        // 예약 + 대기열 등록을 한 원자 단위로. 성공하면 부분 상태가 남을 수 없다.
        Long issued = redis.execute(ISSUE_SCRIPT, issueKeys(userKey, eventId, token),
                token, String.valueOf(tokenTtl), String.valueOf(userId), String.valueOf(eventId));
        if (issued != null && issued == 1L) {
            return tokenResponse(token, eventId);
        }

        // 예약 실패 = 이미 이 유저의 토큰이 있다. 재사용 판단은 읽기 위주라 애플리케이션에 둔다.
        String existing = redis.opsForValue().get(userKey);
        if (existing != null && isReusable(existing, eventId)) {
            return currentOrWaiting(existing, eventId); // 살아있는 토큰(1인1토큰)
        }
        // (1) 입장 후 만료된 죽은 토큰이 유저키에 남아 재예매를 막던 것, 또는
        // (2) 극히 드문 경합(예약 확인~조회 사이 만료) → 소유권을 이 요청이 회수하고 새로 발급.
        // 옛 메타 정리부터 재등록까지 원자적으로(중간 상태 없음).
        List<String> keys = new ArrayList<>(issueKeys(userKey, eventId, token));
        keys.add(QueueKeys.token(existing != null ? existing : token)); // 정리 대상(없으면 무해한 자기 키)
        redis.execute(TAKEOVER_SCRIPT, keys,
                token, String.valueOf(tokenTtl), String.valueOf(userId), String.valueOf(eventId));
        return tokenResponse(token, eventId);
    }

    /**
     * 판매 중인 공연인지 확인한다. <b>대기열 진입의 첫 관문</b>이다.
     *
     * <p>예전에는 이 검사가 없어 <b>이벤트를 조회조차 하지 않았다.</b> 그래서 직접 API를 치면
     * DRAFT·PAUSED·CLOSED 공연은 물론 <b>존재하지도 않는 eventId</b>로도 토큰이 발급됐고,
     * 거기서 좌석 선점·주문·결제까지 이어질 수 있었다.
     *
     * <p>존재 검사가 특히 중요하다 — {@code ISSUE_LUA}가 {@code SADD queue:active-events}를 하고
     * 승격 워커가 그 집합을 1.5초마다 순회하므로, 임의의 id로 발급을 반복하면 <b>Redis 키와
     * 순회 대상이 무한히 쌓인다.</b>
     *
     * <p>⚠️ 진입 경로에 DB 조회가 하나 늘어난다. PK 단건이라 싸지만 공짜는 아니다 —
     * 스파이크(정원의 30배 도착)에서는 그만큼의 조회가 더 발생한다. 측정하지 않았다.
     */
    private void requireBookable(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!event.getStatus().isBookable()) {
            throw new BusinessException(ErrorCode.EVENT_NOT_ON_SALE);
        }
    }

    /** 발급 스크립트 공통 KEYS: 유저키·순번·대기ZSet·토큰메타·활성이벤트. */
    private List<String> issueKeys(String userKey, Long eventId, String token) {
        return List.of(userKey, QueueKeys.seq(eventId), QueueKeys.wait(eventId),
                QueueKeys.token(token), QueueKeys.ACTIVE_EVENTS);
    }

    /**
     * 대기열 이탈: 대기/입장 상태에 따라 슬롯·순번을 정리한다(나가기 실동작).
     * 입장 슬롯 반환은 Lua로 원자화 — admitExp에서 실제로 제거한 요청만 카운트를 줄여
     * 동시 leave나 만료 sweep과 겹쳐도 이중 차감(음수)이 나지 않는다.
     */
    public void leave(String token) {
        Map<Object, Object> meta = redis.opsForHash().entries(QueueKeys.token(token));
        if (meta.isEmpty()) {
            return; // 이미 정리됨
        }
        Long eventId = Long.valueOf((String) meta.get("eventId"));
        Long userId = Long.valueOf((String) meta.get("userId"));

        Long freed = redis.execute(LEAVE_ADMIT_SCRIPT,
                List.of(QueueKeys.admitExp(eventId), QueueKeys.admitCount(eventId), QueueKeys.admit(token)),
                token);
        if (freed == null || freed == 0L) {
            // 입장 상태가 아니었음 → 대기열에서 제거(ZREM은 멱등, 카운터 없음)
            redis.opsForZSet().remove(QueueKeys.wait(eventId), token);
        }
        redis.delete(QueueKeys.token(token));
        redis.delete(QueueKeys.user(eventId, userId));
    }

    /** 좌석(S04) 게이트: 이 토큰이 해당 이벤트에 입장(ADMITTED)했는가. */
    public boolean isAdmitted(String token, Long eventId) {
        if (token == null) {
            return false;
        }
        // admitExp는 이벤트 단위 ZSet이라 이 검사 자체가 소속 이벤트를 보장한다.
        Double expiresAt = redis.opsForZSet().score(QueueKeys.admitExp(eventId), token);
        if (expiresAt != null && expiresAt > Instant.now().getEpochSecond()) {
            return true;
        }
        // 폴백: admit 키는 토큰만 보므로 다른 이벤트의 입장으로 좌석을 잡지 못하게 소속을 확인한다.
        if (!Boolean.TRUE.equals(redis.hasKey(QueueKeys.admit(token)))) {
            return false;
        }
        Object tokenEvent = redis.opsForHash().get(QueueKeys.token(token), "eventId");
        return tokenEvent != null && eventId.equals(Long.valueOf((String) tokenEvent));
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

    /**
     * 유저키에 남아있는 기존 토큰이 재사용 가능한(살아있는) 토큰인가.
     * WAITING/ADMITTED면 재사용(1인1토큰 유지). 입장 후 만료돼 wait/admit 어디에도 없는
     * '죽은 토큰'이면 false → 호출부가 정리하고 새 토큰을 발급(재예매 허용).
     * 단, 아직 wait 등록 전인 '경합 중 신규 토큰'은 메타가 없어 EXPIRED로 보이므로
     * 이 경우엔 true를 반환해 중복 발급을 막는다.
     */
    private boolean isReusable(String token, Long eventId) {
        if (statusOf(token, eventId) != QueueStatus.EXPIRED) {
            return true; // WAITING 또는 ADMITTED
        }
        return !Boolean.TRUE.equals(redis.hasKey(QueueKeys.token(token))); // 메타 없으면 경합 중 신규 → 재사용
    }

    private QueueStatus statusOf(String token, Long eventId) {
        if (admittedNow(token, eventId)) {
            return QueueStatus.ADMITTED;
        }
        Long r = redis.opsForZSet().rank(QueueKeys.wait(eventId), token);
        return r != null ? QueueStatus.WAITING : QueueStatus.EXPIRED;
    }

    /**
     * 입장 여부 판정의 단일 규칙. **admit 키가 빠른 경로이고 권위는 admitExp다.**
     *
     * 승격은 pop·카운트·admitExp 등록까지 한 Lua로 확정되고, admit 키는 그 뒤에 붙는다.
     * 따라서 확정됐지만 admit 키가 아직 없는 순간이 존재하며, 그 창에서 admit 키만 보면
     * "입장 안 했다"로 오판한다 — 예전에 진입 응답이 EXPIRED로 나가던 원인이다([[TS-024]]).
     * admitExp는 이벤트 단위 ZSet이라 소속 이벤트 검사도 겸한다.
     */
    private boolean admittedNow(String token, Long eventId) {
        if (Boolean.TRUE.equals(redis.hasKey(QueueKeys.admit(token)))) {
            return true; // 대부분 여기서 끝난다(왕복 1회)
        }
        Double expiresAt = redis.opsForZSet().score(QueueKeys.admitExp(eventId), token);
        return expiresAt != null && expiresAt > Instant.now().getEpochSecond();
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
