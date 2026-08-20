package com.flowticket.auth.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 로그아웃된 Access Token을 남은 TTL 동안 블랙리스트로 관리.
 *
 * <p><b>Redis 장애 시의 방침을 여기서 정한다(ADR-016).</b> 읽기와 쓰기를 다르게 다룬다.
 *
 * <ul>
 *   <li><b>읽기({@link #isBlacklisted})는 fail-open</b> — 확인하지 못하면 "블랙리스트가 아니다"로
 *       간주하고 요청을 통과시킨다. 이 검사는 {@code JwtAuthenticationFilter} 안에 있어서,
 *       예외를 그대로 올리면 <b>Bearer 헤더가 달린 모든 요청</b>이 500이 된다 — 공개 경로인
 *       좌석·공연 조회까지 포함해서다(로그인한 브라우저는 공개 경로에도 토큰을 보낸다).</li>
 *   <li><b>쓰기({@link #blacklist})는 fail-loud</b> — 실패를 삼키지 않는다. 취소를 기록하지
 *       못했는데 "로그아웃됐다"고 답하면 거짓말이 된다.</li>
 * </ul>
 *
 * <p><b>fail-open이 무엇을 포기하는가</b>: Redis를 못 읽는 동안, 이미 로그아웃된 access token이
 * <b>남은 TTL(최대 30분, {@code jwt.access-token-ttl}) 동안</b> 다시 통한다.
 *
 * <p><b>그럼에도 fail-open인 이유</b>: 같은 장애 구간에서 {@code AuthService.logout()}은 어차피
 * 실패한다 — {@code TokenService.revoke()}와 이 클래스의 쓰기가 모두 Redis다. 즉 <b>fail-closed로
 * 막아도 "취소할 수 있는 상태"가 보존되지 않는다.</b> 얻는 것 없이 "취소가 지연된다"를
 * "서비스가 멈춘다"로 바꿀 뿐이다.
 *
 * <p><b>이 판단이 뒤집히는 조건</b>: 블랙리스트가 로그아웃 외의 것을 막게 되면(예: 침해 계정
 * 강제 차단, 관리자 세션 킬) 지연의 대가가 달라진다. 그때는 다시 결정해야 한다.
 */
@Slf4j
@Service
public class TokenBlacklistService {

    private static final String PREFIX = "blacklist:";

    /** Prometheus에서는 auth_blacklist_check_failures_total이 된다. */
    private static final String FAILURE_METRIC = "auth.blacklist.check.failures";

    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;

    public TokenBlacklistService(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.meterRegistry = meterRegistry;
    }

    /** 실패를 삼키지 않는다 — 취소를 기록하지 못했다면 로그아웃도 성공이 아니다. */
    public void blacklist(String accessToken, long remainingSeconds) {
        if (remainingSeconds <= 0) {
            return;
        }
        redis.opsForValue().set(PREFIX + accessToken, "1", Duration.ofSeconds(remainingSeconds));
    }

    /**
     * 확인하지 못하면 {@code false}(=통과). 위 클래스 주석의 fail-open 방침이다.
     *
     * <p>지표를 올리는 이유: 이 실패는 <b>사용자에게 보이지 않는다.</b> 요청은 전부 성공하고
     * 취소만 조용히 안 먹는다. 세지 않으면 알아챌 방법이 없다.
     */
    public boolean isBlacklisted(String accessToken) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + accessToken));
        } catch (Exception e) {
            meterRegistry.counter(FAILURE_METRIC).increment();
            log.warn("[auth] 블랙리스트 조회 실패 — 통과시킨다(fail-open). "
                    + "이 구간에는 로그아웃된 토큰이 남은 TTL 동안 유효하다: {}", e.toString());
            return false;
        }
    }
}
