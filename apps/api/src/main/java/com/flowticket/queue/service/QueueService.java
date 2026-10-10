package com.flowticket.queue.service;

import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.queue.domain.QueueStatus;
import com.flowticket.queue.dto.QueueStatusResponse;
import com.flowticket.queue.dto.QueueTokenResponse;
import java.time.Clock;
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

    // 이탈 원자화: 입장 슬롯 반환·대기열 제거·메타 삭제·유저키 정리를 한 번에.
    //   - 입장 슬롯은 admitExp에서 실제로 제거한 요청만 카운트 감소(중복 DECR 방지) — 동시 leave / leave↔만료 sweep의 이중 차감 방지.
    //   - 유저키는 **아직 이 토큰을 가리킬 때만** 지운다(CAS). 예전에는 메타를 읽은 뒤 유저키를 무조건 지워, 그 사이 같은 회원이
    //     새 토큰을 받았으면 새 토큰의 유저키까지 지웠다 — 그러면 다음 진입이 SET NX에 성공해 한 회원의 활성 토큰이 둘이 된다.
    // KEYS: admitExp, admitCount, admitKey, wait, tokenMeta, userKey / ARGV: token
    // 반환: 1 = 입장 슬롯을 반환함, 0 = 대기 중이었거나 이미 정리됨
    private static final String LEAVE_LUA = """
            local freed = 0
            if redis.call('ZREM', KEYS[1], ARGV[1]) == 1 then
              redis.call('DECR', KEYS[2])
              freed = 1
            else
              redis.call('ZREM', KEYS[4], ARGV[1])
            end
            redis.call('DEL', KEYS[3])
            redis.call('DEL', KEYS[5])
            if redis.call('GET', KEYS[6]) == ARGV[1] then
              redis.call('DEL', KEYS[6])
            end
            return freed
            """;
    private static final DefaultRedisScript<Long> LEAVE_SCRIPT =
            new DefaultRedisScript<>(LEAVE_LUA, Long.class);

    // 토큰 발급 원자화: 유저키 예약(SET NX)과 대기열 등록(순번·ZSet·메타·TTL·활성이벤트)을 한 번에 실행한다.
    // 예전엔 예약과 등록이 여러 왕복으로 나뉘어, 중간에 Redis 장애가 나면 "유저키는 있는데 대기 ZSet엔
    // 없는" 부분 상태가 남을 수 있었다(승격되지 않는 유령 토큰). 예약 실패(이미 토큰 보유)면 {0}을 반환한다.
    // 성공하면 {1, 순번(1부터), 전체 대기 수}를 함께 돌려준다 — 방금 넣은 토큰은 이 스크립트가 끝나기 전에는
    // 승격될 수 없어(승격도 Lua라 원자적으로 끼어들지 못한다) 상태가 WAITING으로 정해져 있다. 예전에는 발급 뒤
    // 응답을 만들려고 입장 여부(EXISTS·ZSCORE)·순번(ZRANK 2회)·전체 수(ZCARD)를 따로 읽어 진입 1건이 Redis를
    // 7번 왕복했고, 측정 세션 20261005-1440의 4,000/s에서 Redis 메인 스레드가 요청당 0.172ms를 써 단일 스레드 상한이
    // 약 5,800/s로 계산됐다. 같은 키(KEYS[3])만 읽으므로 Lua 안에서 키를 만들지 않는다.
    // 메타에 발급 시각(issuedAt, epoch 초)을 남긴다 — 폴링 연장의 절대 상한(queue.token-max-lifetime)을 세는 기준이다.
    // KEYS: userKey, seqKey, waitKey, tokenKey, activeEvents / ARGV: token, ttl, userId, eventId, nowEpochSeconds
    private static final String ISSUE_LUA = """
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2]) then
              local seq = redis.call('INCR', KEYS[2])
              redis.call('ZADD', KEYS[3], seq, ARGV[1])
              redis.call('HSET', KEYS[4], 'userId', ARGV[3], 'eventId', ARGV[4], 'issuedAt', ARGV[5])
              redis.call('EXPIRE', KEYS[4], ARGV[2])
              redis.call('SADD', KEYS[5], ARGV[4])
              return {1, redis.call('ZRANK', KEYS[3], ARGV[1]) + 1, redis.call('ZCARD', KEYS[3])}
            end
            return {0}
            """;
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> ISSUE_SCRIPT = new DefaultRedisScript<>(ISSUE_LUA, List.class);

    // 죽은 토큰 회수 후 재발급(소유권 이전). 등록 절차는 ISSUE와 같고, 옛 토큰 정리(메타 KEYS[6]·대기열 원소)까지 같은 원자 단위다.
    // **CAS**: 유저키가 아직 호출부가 읽은 값(ARGV[5], 없었으면 '')일 때만 덮어쓴다. 예전에는 확인 없이 덮어써, 같은 회원의
    // 동시 요청 여럿이 모두 "죽은 토큰"을 보면 각자 새 토큰을 만들었다 — 유저키는 마지막 것만 가리키고 나머지는 대기열에 남아
    // 한 회원이 줄을 여러 번 선다. CAS에 지면 0을 반환하고 호출부가 이긴 요청의 토큰을 다시 판정한다.
    // KEYS: userKey, seqKey, waitKey, newTokenMeta, activeEvents, oldTokenMeta / ARGV: token, ttl, userId, eventId, expectedOld, nowEpochSeconds
    private static final String TAKEOVER_LUA = """
            local cur = redis.call('GET', KEYS[1])
            if cur == false then cur = '' end
            if cur ~= ARGV[5] then
              return 0
            end
            if ARGV[5] ~= '' then
              redis.call('ZREM', KEYS[3], ARGV[5])
              redis.call('DEL', KEYS[6])
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            local seq = redis.call('INCR', KEYS[2])
            redis.call('ZADD', KEYS[3], seq, ARGV[1])
            redis.call('HSET', KEYS[4], 'userId', ARGV[3], 'eventId', ARGV[4], 'issuedAt', ARGV[6])
            redis.call('EXPIRE', KEYS[4], ARGV[2])
            redis.call('SADD', KEYS[5], ARGV[4])
            return 1
            """;
    private static final DefaultRedisScript<Long> TAKEOVER_SCRIPT =
            new DefaultRedisScript<>(TAKEOVER_LUA, Long.class);

    // 상태 한 번 읽기: 입장 여부·순번·전체 수를 한 Lua로. 판정 규칙은 admittedNow·좌석 게이트(isAdmitted)와 같다 —
    // **admitExp에 있고**, 입장창이 남았거나(점수 > 지금) 아직 회수 전이라 admit 키가 남아 있으면 ADMITTED.
    // 예전에는 admit 키만 있어도 ADMITTED로 답해, 회수(admitExp에서 제거)된 뒤 admit 키 TTL이 남은 순간에 상태 조회는 ADMITTED인데
    // 좌석 게이트는 거부했다. 이제 셋이 같은 규칙이다. admit 키가 아직 없는 승격 직후(TS-024)는 점수로 ADMITTED다.
    // 예전에는 상태 조회 1건이 메타(HGETALL)·EXISTS·ZSCORE·ZRANK·ZCARD·ZRANK로
    // Redis를 6번 왕복했다. 대기 상태를 폴링 주 경로로 옮기면(ADR-023 §2) 대기자 전원이 이 경로를 주기적으로 부르므로
    // 메타 조회(eventId를 알아야 키를 만든다) 1회 + 이 스크립트 1회로 줄인다. 키는 모두 KEYS로 받는다(Lua 안에서 만들지 않는다).
    // admit 키는 토큰 단위라 이벤트 키와 슬롯이 다를 수 있다 — ISSUE_LUA도 여러 슬롯의 키를 함께 쓴다(클러스터 모드 아님).
    // KEYS: admitKey, admitExpKey, waitKey / ARGV: token, nowEpochSeconds
    // 반환: {상태(0 EXPIRED·1 WAITING·2 ADMITTED), 순번(WAITING일 때 1부터, 아니면 0), 전체 대기 수}
    private static final String STATUS_LUA = """
            local total = redis.call('ZCARD', KEYS[3])
            local exp = redis.call('ZSCORE', KEYS[2], ARGV[1])
            if exp and (tonumber(exp) > tonumber(ARGV[2]) or redis.call('EXISTS', KEYS[1]) == 1) then
              return {2, 0, total}
            end
            local r = redis.call('ZRANK', KEYS[3], ARGV[1])
            if r then
              return {1, r + 1, total}
            end
            return {0, 0, total}
            """;
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> STATUS_SCRIPT = new DefaultRedisScript<>(STATUS_LUA, List.class);

    // 폴링용 상태 조회: STATUS_LUA와 판정이 같고, 대기 중(WAITING)이면 토큰 수명을 연장한다(유휴 만료).
    // 예전에는 메타·유저키 TTL이 발급 시각부터 고정이라(queue.token-ttl 1,800초) 30분 넘게 기다리면 대기 중에도 만료됐다 —
    // 상태 조회가 QUEUE_EXPIRED가 되고, 대기열 원소는 남아 순서를 차지하다가 승격돼 입장 슬롯을 비운 채 쥐었다.
    // 이제 폴링하는 동안은 자격을 유지하고, 폴링이 끊기면 마지막 폴링 뒤 token-ttl이 지나 만료된다.
    //   - 연장 값(ARGV[3])은 호출부가 계산한다: min(token-ttl, 발급 시각 + 절대 상한 − 지금). 0이면 연장하지 않는다
    //     (상한을 넘었거나 발급 시각이 없는 옛 토큰).
    //   - 남은 수명이 ARGV[4](token-ttl의 절반) 아래일 때만 연장한다 — 대부분의 조회는 TTL 1회만 더 한다(폴링은 최대 30초 간격).
    //   - 유저키는 아직 이 토큰을 가리킬 때만 함께 연장한다(1인 1토큰 — 다른 토큰의 키를 늘리지 않는다).
    // KEYS: admitKey, admitExpKey, waitKey, tokenMeta, userKey / ARGV: token, nowEpochSeconds, extendTtl, refreshBelow
    private static final String STATUS_POLL_LUA = """
            local total = redis.call('ZCARD', KEYS[3])
            local exp = redis.call('ZSCORE', KEYS[2], ARGV[1])
            if exp and (tonumber(exp) > tonumber(ARGV[2]) or redis.call('EXISTS', KEYS[1]) == 1) then
              return {2, 0, total}
            end
            local r = redis.call('ZRANK', KEYS[3], ARGV[1])
            if r then
              local extend = tonumber(ARGV[3])
              if extend > 0 then
                local ttl = redis.call('TTL', KEYS[4])
                if ttl >= 0 and ttl < tonumber(ARGV[4]) then
                  redis.call('EXPIRE', KEYS[4], extend)
                  if redis.call('GET', KEYS[5]) == ARGV[1] then
                    redis.call('EXPIRE', KEYS[5], extend)
                  end
                end
              end
              return {1, r + 1, total}
            end
            return {0, 0, total}
            """;
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> STATUS_POLL_SCRIPT = new DefaultRedisScript<>(STATUS_POLL_LUA, List.class);

    private final StringRedisTemplate redis;
    private final BookableEventCache bookableEvents;
    private final int capacity;
    private final long tokenTtl;
    /** 폴링 연장의 절대 상한(초, 발급 시각부터). 폴링을 계속해도 이 시간이 지나면 더 연장하지 않는다. */
    private final long tokenMaxLifetime;
    private final long admitIntervalMs;
    /** 다음 상태 조회까지 최소 대기(ms): 앞쪽 순번 값과 상한 — retryAfterMs. */
    private final long pollMinMs;
    private final long pollMaxMs;

    private final Clock clock;
    private final QueueMetrics metrics;

    public QueueService(StringRedisTemplate redis, BookableEventCache bookableEvents,
                        @Value("${queue.capacity:100}") int capacity,
                        @Value("${queue.token-ttl:1800}") long tokenTtl,
                        @Value("${queue.token-max-lifetime:21600}") long tokenMaxLifetime,
                        @Value("${queue.admit-interval-ms:1500}") long admitIntervalMs,
                        @Value("${queue.poll-min-ms:2000}") long pollMinMs,
                        @Value("${queue.poll-max-ms:30000}") long pollMaxMs,
                        Clock clock, QueueMetrics metrics) {
        this.clock = clock;
        this.metrics = metrics;
        this.redis = redis;
        this.bookableEvents = bookableEvents;
        this.capacity = capacity;
        this.tokenTtl = tokenTtl;
        this.tokenMaxLifetime = tokenMaxLifetime;
        this.admitIntervalMs = admitIntervalMs;
        this.pollMinMs = pollMinMs;
        this.pollMaxMs = pollMaxMs;
    }

    /**
     * 대기 진입. 이미 활성 토큰이 있으면 그 토큰을 반환(1인1토큰).
     * user 키를 SET NX로 원자 예약해, 같은 유저 동시 요청(더블클릭)에도 토큰이 하나만 생기게 한다.
     */
    public QueueTokenResponse issue(Long userId, Long eventId) {
        requireBookable(eventId);
        String userKey = QueueKeys.user(eventId, userId);
        String token = UUID.randomUUID().toString();

        // 경합에 지면(다른 요청이 그 사이 유저키를 바꿈) 처음부터 다시 판정한다. 매번 상태가 바뀌어야 지므로 몇 번이면 끝난다.
        for (int attempt = 0; attempt < MAX_ISSUE_ATTEMPTS; attempt++) {
            // 예약 + 대기열 등록을 한 원자 단위로. 성공하면 부분 상태가 남을 수 없다.
            List<?> issued = redis.execute(ISSUE_SCRIPT, issueKeys(userKey, eventId, token),
                    token, String.valueOf(tokenTtl), String.valueOf(userId), String.valueOf(eventId), nowSeconds());
            if (issued != null && issued.size() == 3 && ((Number) issued.get(0)).longValue() == 1L) {
                // 신규 발급: 상태·순번·전체 수가 스크립트 결과에 있다(추가 왕복 없음 — ISSUE_LUA 주석).
                long rank = ((Number) issued.get(1)).longValue();
                return new QueueTokenResponse(token, QueueStatus.WAITING.name(), rank,
                        ((Number) issued.get(2)).longValue(), retryAfterMs(QueueStatus.WAITING, rank));
            }

            // 예약 실패 = 이미 이 유저의 토큰이 있다. 재사용 판단은 읽기 위주라 애플리케이션에 둔다.
            String existing = redis.opsForValue().get(userKey);
            if (existing != null && isReusable(existing, eventId)) {
                return currentOrWaiting(existing, eventId); // 살아있는 토큰(1인1토큰)
            }
            // (1) 입장 후 만료된 죽은 토큰이 유저키에 남아 재예매를 막던 것, 또는
            // (2) 극히 드문 경합(예약 확인~조회 사이 만료) → 소유권을 이 요청이 회수하고 새로 발급.
            // 유저키가 방금 읽은 값 그대로일 때만(CAS) 옛 토큰 정리부터 재등록까지 원자적으로 한다.
            List<String> keys = new ArrayList<>(issueKeys(userKey, eventId, token));
            keys.add(QueueKeys.token(existing != null ? existing : token)); // 정리 대상(없으면 무해한 자기 키)
            Long took = redis.execute(TAKEOVER_SCRIPT, keys,
                    token, String.valueOf(tokenTtl), String.valueOf(userId), String.valueOf(eventId),
                    existing != null ? existing : "", nowSeconds());
            if (took != null && took == 1L) {
                return tokenResponse(token, eventId);
            }
            // CAS에 졌다: 다른 요청이 유저키를 먼저 바꿨다. 다음 시도에서 그 토큰을 다시 판정한다.
        }
        throw new IllegalStateException("대기열 토큰 발급 경합이 " + MAX_ISSUE_ATTEMPTS + "회 연속됐다(eventId=" + eventId + ")");
    }

    /** 발급 경합 재시도 상한. 한 번 지려면 그 사이 다른 요청이 유저키를 바꿔야 해서, 정상 상황에서는 1~2회로 끝난다. */
    private static final int MAX_ISSUE_ATTEMPTS = 5;

    /**
     * 판매 중인 공연인지 확인한다. 대기열 진입의 첫 관문이다.
     *
     * 이 검사가 없으면 API를 직접 쳐서 DRAFT·PAUSED·CLOSED 공연이나 존재하지 않는 eventId로도
     * 토큰을 받고, 좌석 선점·주문·결제까지 이어갈 수 있다.
     *
     * 존재 검사가 특히 중요하다. ISSUE_LUA가 SADD queue:active-events를 하고
     * 승격 워커가 그 집합을 1.5초마다 순회하므로, 임의의 id로 발급을 반복하면 Redis 키와
     * 순회 대상이 무한히 쌓인다.
     *
     * 진입 경로에 DB 조회가 하나 늘어난다(측정: 진입 1건당 커넥션 획득 1.01회). 버스트에서는 진입 요청마다 그대로 DB 부하가 되어,
     * 짧은 TTL로 파드 메모리에 기억할 수 있게 했다(BookableEventCache — 기본은 꺼져 있어 매번 DB).
     */
    private void requireBookable(Long eventId) {
        if (!bookableEvents.status(eventId).isBookable()) {
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
     * 입장 슬롯 반환은 Lua로 원자화: admitExp에서 실제로 제거한 요청만 카운트를 줄여
     * 동시 leave나 만료 sweep과 겹쳐도 이중 차감(음수)이 나지 않는다.
     */
    public void leave(String token, Long requesterId) {
        Map<Object, Object> meta = redis.opsForHash().entries(QueueKeys.token(token));
        if (meta.isEmpty()) {
            return; // 이미 정리됨
        }
        Long eventId = Long.valueOf((String) meta.get("eventId"));
        Long userId = Long.valueOf((String) meta.get("userId"));
        // 본인 토큰만 이탈시킨다. 토큰(비밀 UUID)을 알게 된 다른 회원이 남의 대기·입장을 지우지 못하게 한다.
        if (!userId.equals(requesterId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        // 슬롯 반환(또는 대기열 제거)·메타 삭제·유저키 CAS 삭제를 한 원자 단위로(LEAVE_LUA).
        Long freed = redis.execute(LEAVE_SCRIPT,
                List.of(QueueKeys.admitExp(eventId), QueueKeys.admitCount(eventId), QueueKeys.admit(token),
                        QueueKeys.wait(eventId), QueueKeys.token(token), QueueKeys.user(eventId, userId)),
                token);
        if (freed != null && freed == 1L) {
            QueueAudit.leftAdmitted(eventId, token, clock.millis());
        }
    }

    /**
     * 좌석 선점 게이트: 이 토큰이 해당 이벤트에 입장(ADMITTED)했고, 요청한 회원이 그 토큰의 주인인가.
     *
     * 소유자 확인이 없으면 입장 토큰 하나를 여러 계정이 함께 쓸 수 있다 — 한 명이 대기열을 통과하면 다른 계정들이
     * 그 토큰으로 대기 없이 좌석을 잡는다(1인 한도는 계정 단위라 막지 못한다). 주인은 발급 때 토큰 메타에 남긴 userId다.
     * 메타는 승격 때 입장창보다 오래 살도록 늘린다(QueueAdmissionService.admit) — 그래서 입장창 안에서 메타가 없으면
     * 이미 죽은 토큰이 승격된 경우라 거부한다.
     */
    public boolean isAdmitted(String token, Long eventId, Long requesterId) {
        if (token == null || requesterId == null) {
            return false;
        }
        List<Object> meta = redis.opsForHash().multiGet(QueueKeys.token(token), List.<Object>of("userId", "eventId"));
        Object owner = meta.get(0);
        if (owner == null || !requesterId.equals(Long.valueOf((String) owner))) {
            return false;
        }
        // admitExp는 이벤트 단위 ZSet이라 이 검사 자체가 소속 이벤트를 보장한다.
        Double expiresAt = redis.opsForZSet().score(QueueKeys.admitExp(eventId), token);
        if (expiresAt != null && expiresAt > Instant.now(clock).getEpochSecond()) {
            return true;
        }
        // 폴백: admit 키는 토큰만 보므로 다른 이벤트의 입장으로 좌석을 잡지 못하게 소속을 확인한다.
        if (!Boolean.TRUE.equals(redis.hasKey(QueueKeys.admit(token)))) {
            return false;
        }
        Object tokenEvent = meta.get(1);
        boolean sameEvent = tokenEvent != null && eventId.equals(Long.valueOf((String) tokenEvent));
        if (expiresAt == null) {
            // 이미 회수(또는 이탈)돼 admitExp에 없는 토큰은 admit 키가 남아 있어도 통과시키지 않는다.
            // 회수는 admitExp만 지우고 admit 키는 TTL까지 남기며(키 TTL은 승격 스크립트 뒤에 시작해 점수보다
            // 늦게 끝난다), 같은 틱에서 빈 슬롯이 다른 사람에게 다시 승격된다. 여기서 통과시키면 그 창 동안
            // 실효 입장자가 정원을 넘는다(loadtest-100k-plan §3.3). 게이트가 admitExp 원소만 받으면 실효 입장자는
            // 항상 admitExp의 부분집합이라, over-admit 판정(admitExp 원소 수 > 정원)이 실효 입장 초과까지 덮는다.
            // 거부한 횟수를 센다 — 0보다 크면 그 창이 실제로 생겼다는 뜻이다(주인 확인을 통과한 요청만 여기 온다).
            if (sameEvent) {
                metrics.gateFallback().increment();
            }
            return false;
        }
        // 점수가 있고 이미 지난(만료됐지만 아직 회수 전) 토큰은 admit 키로 통과시킨다. 아직 카운터와 admitExp에
        // 남아 슬롯을 쥔 상태라 정원 초과가 아니고, 다음 회수 틱(최대 승격 주기)까지의 짧은 구간이다.
        return sameEvent;
    }

    /** 소유자가 아직 대기열 등록 전(경합)이면 EXPIRED로 보일 수 있어 WAITING으로 낙관 처리. */
    private QueueTokenResponse currentOrWaiting(String token, Long eventId) {
        QueueStatus st = statusOf(token, eventId);
        if (st == QueueStatus.EXPIRED) {
            long rank = rankOf(token, eventId);
            return new QueueTokenResponse(token, QueueStatus.WAITING.name(), rank, card(eventId),
                    retryAfterMs(QueueStatus.WAITING, rank));
        }
        return tokenResponse(token, eventId);
    }

    /**
     * 상태 폴링. 토큰 메타가 사라졌으면(수명 만료) QUEUE_EXPIRED. Redis 2왕복(메타 HMGET + STATUS_POLL_LUA).
     * 대기 중이면 토큰 수명을 연장한다(STATUS_POLL_LUA 주석) — 폴링하는 동안 대기 자격이 유지되고, 발급 뒤
     * queue.token-max-lifetime이 지나면 더 연장하지 않는다.
     */
    public QueueStatusResponse status(String token) {
        List<Object> meta = redis.opsForHash().multiGet(QueueKeys.token(token), List.<Object>of("eventId", "userId", "issuedAt"));
        if (meta.get(0) == null) {
            throw new BusinessException(ErrorCode.QUEUE_EXPIRED);
        }
        Long eventId = Long.valueOf((String) meta.get(0));
        long now = Instant.now(clock).getEpochSecond();
        long extend = 0; // 발급 시각이 없는 옛 토큰은 연장하지 않는다(예전 동작)
        if (meta.get(1) != null && meta.get(2) != null) {
            long remainingLifetime = Long.parseLong((String) meta.get(2)) + tokenMaxLifetime - now;
            extend = Math.max(0, Math.min(tokenTtl, remainingLifetime));
        }
        // 연장하지 않는 경우(extend 0)에도 KEYS 자리를 채운다 — 스크립트가 이 키를 읽지 않는다.
        String userKey = QueueKeys.user(eventId, meta.get(1) != null ? Long.valueOf((String) meta.get(1)) : 0L);
        Snapshot s = toSnapshot(redis.execute(STATUS_POLL_SCRIPT,
                List.of(QueueKeys.admit(token), QueueKeys.admitExp(eventId), QueueKeys.wait(eventId),
                        QueueKeys.token(token), userKey),
                token, String.valueOf(now), String.valueOf(extend), String.valueOf(tokenTtl / 2)));
        long eta = s.status() == QueueStatus.WAITING ? etaSeconds(s.rank()) : 0;
        return new QueueStatusResponse(s.rank(), s.total(), eta, s.status().name(),
                retryAfterMs(s.status(), s.rank()));
    }

    private QueueTokenResponse tokenResponse(String token, Long eventId) {
        Snapshot s = snapshot(token, eventId);
        return new QueueTokenResponse(token, s.status().name(), s.rank(), s.total(),
                retryAfterMs(s.status(), s.rank()));
    }

    private record Snapshot(QueueStatus status, long rank, long total) {
    }

    private Snapshot snapshot(String token, Long eventId) {
        return toSnapshot(redis.execute(STATUS_SCRIPT,
                List.of(QueueKeys.admit(token), QueueKeys.admitExp(eventId), QueueKeys.wait(eventId)),
                token, nowSeconds()));
    }

    private String nowSeconds() {
        return String.valueOf(Instant.now(clock).getEpochSecond());
    }

    private Snapshot toSnapshot(List<?> r) {
        if (r == null || r.size() != 3) {
            throw new IllegalStateException("STATUS_LUA 결과가 없다(파이프라인·트랜잭션 안에서 호출됨)");
        }
        long code = ((Number) r.get(0)).longValue();
        QueueStatus st = code == 2 ? QueueStatus.ADMITTED : code == 1 ? QueueStatus.WAITING : QueueStatus.EXPIRED;
        return new Snapshot(st, ((Number) r.get(1)).longValue(), ((Number) r.get(2)).longValue());
    }

    /**
     * 다음 상태 조회까지 기다릴 시간(ms) — 클라이언트는 이보다 일찍 다시 묻지 않는다(최소 대기, ADR-023 §2 — 사용자 결정 2~30초).
     *
     * 앞쪽(순번 ≤ 정원 × 2)은 pollMinMs. 그 뒤는 "승격 주기마다 정원만큼 빠진다고 볼 때 내가 입장하기까지 걸리는 시간"
     * = ceil(순번 ÷ 정원) × 승격 주기를 [pollMinMs, pollMaxMs]로 자른다. 엄밀한 하한은 아니다 — 승격 워커가 파드마다 돌아
     * 슬롯이 빨리 비면 한 주기에 정원보다 많이 빠질 수 있고, 응답 직후 첫 틱이 바로 올 수도 있다. 그만큼 입장 인지가 늦어질 수
     * 있어 최종 시험에서 입장 인지 지연을 잰다. 입장·만료(종료 상태)면 0(더 묻지 않는다).
     * 대기 10만 명(정적 대기열 가정)이면 앞쪽 200명 2초 + 201~2,000명 4.5~30초 + 나머지 30초로
     * 조회 부하는 약 3,500 req/s다(계산값, jitter 없이 — jitter 평균 +10%면 약 3,200).
     */
    long retryAfterMs(QueueStatus status, long rank) {
        if (status != QueueStatus.WAITING) {
            return 0;
        }
        int cap = Math.max(capacity, 1);
        if (rank <= 2L * cap) {
            return pollMinMs;
        }
        long untilFront = ((rank + cap - 1) / cap) * admitIntervalMs;
        return Math.min(pollMaxMs, Math.max(pollMinMs, untilFront));
    }

    /**
     * 유저키에 남아있는 기존 토큰이 재사용 가능한(살아있는) 토큰인가.
     * WAITING/ADMITTED면 재사용(1인1토큰 유지). 입장 후 만료돼 wait/admit 어디에도 없는
     * '죽은 토큰'이면 false → 호출부가 회수(TAKEOVER, 유저키 CAS)하고 새 토큰을 발급(재예매 허용).
     *
     * 판정은 STATUS_LUA 한 번의 스냅샷으로 한다. 입장 여부·대기 여부를 따로 읽으면 그 사이 승격이 끼어 막 입장한 토큰을
     * EXPIRED로 오판하고, 회수가 입장 토큰의 메타를 지울 수 있다.
     * 예전에는 "메타가 없으면 등록 중인 신규 토큰"으로 보고 재사용했지만, 발급·회수가 유저키와 메타를 한 Lua로 함께 쓰므로
     * 그 상태는 생기지 않는다. 오히려 다른 요청이 그 토큰을 막 회수·이탈시킨 경우를 재사용으로 오판해 죽은 토큰을 돌려줬다(TS-044).
     * 메타가 없으면 회수로 넘긴다 — 유저키가 그새 바뀌었으면 CAS에 져서 다시 판정하고, 그대로면 고아 키라 덮어쓰는 게 맞다.
     */
    private boolean isReusable(String token, Long eventId) {
        return snapshot(token, eventId).status() != QueueStatus.EXPIRED;
    }

    private QueueStatus statusOf(String token, Long eventId) {
        if (admittedNow(token, eventId)) {
            return QueueStatus.ADMITTED;
        }
        Long r = redis.opsForZSet().rank(QueueKeys.wait(eventId), token);
        return r != null ? QueueStatus.WAITING : QueueStatus.EXPIRED;
    }

    /**
     * 입장 여부 판정의 단일 규칙 — STATUS_LUA·STATUS_POLL_LUA·좌석 게이트(isAdmitted)와 같다.
     * admitExp에 있어야 하고(권위), 입장창이 남았거나(점수 > 지금) 아직 회수 전이라 admit 키가 남아 있으면 입장이다.
     *
     * 승격은 pop·카운트·admitExp 등록까지 한 Lua로 확정되고, admit 키는 그 뒤에 붙는다. 그래서 admit 키가 아직 없는
     * 승격 직후에도 점수로 입장이다(TS-024). 반대로 회수는 admitExp만 지우고 admit 키는 TTL까지 남기므로, admit 키만으로
     * 판정하면 회수된 토큰을 입장으로 오판한다 — 좌석 게이트는 거부하는데 상태는 ADMITTED가 되던 불일치(TS-047).
     * admitExp는 이벤트 단위 ZSet이라 소속 이벤트 검사도 겸한다.
     */
    private boolean admittedNow(String token, Long eventId) {
        Double expiresAt = redis.opsForZSet().score(QueueKeys.admitExp(eventId), token);
        if (expiresAt == null) {
            return false; // 회수·이탈됐거나 입장한 적 없음
        }
        if (expiresAt > Instant.now(clock).getEpochSecond()) {
            return true;
        }
        // 입장창은 지났지만 아직 회수 전 — 좌석 게이트와 같이 admit 키가 남아 있으면 입장으로 본다(다음 회수 틱까지).
        return Boolean.TRUE.equals(redis.hasKey(QueueKeys.admit(token)));
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
