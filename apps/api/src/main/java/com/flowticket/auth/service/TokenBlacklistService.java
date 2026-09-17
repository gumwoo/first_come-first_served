package com.flowticket.auth.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 로그아웃된 Access Token을 남은 TTL 동안 블랙리스트로 관리.
 *
 * <p>Redis 장애 시 읽기({@link #isBlacklisted})는 fail-open, 쓰기({@link #blacklist})는 실패를 전파한다.
 * 필터 안의 읽기가 예외를 올리면 공개 경로까지 Bearer 요청이 전부 500이 된다(ADR-016).
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

    /** 실패를 삼키지 않는다. 취소를 기록하지 못했다면 로그아웃도 성공이 아니다. */
    public void blacklist(String accessToken, long remainingSeconds) {
        if (remainingSeconds <= 0) {
            return;
        }
        redis.opsForValue().set(PREFIX + accessToken, "1", Duration.ofSeconds(remainingSeconds));
    }

    /**
     * 확인하지 못하면 {@code false}(=통과). 위 클래스 주석의 fail-open 방침이다.
     *
     * <p>지표를 올리는 이유: 이 실패는 사용자에게 보이지 않는다. 요청은 전부 성공하고
     * 로그아웃 취소만 반영되지 않는다. 세지 않으면 알아챌 방법이 없다.
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
