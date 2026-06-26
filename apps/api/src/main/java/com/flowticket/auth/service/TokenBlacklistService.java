package com.flowticket.auth.service;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 로그아웃된 Access Token을 남은 TTL 동안 블랙리스트로 관리. */
@Service
public class TokenBlacklistService {

    private static final String PREFIX = "blacklist:";

    private final StringRedisTemplate redis;

    public TokenBlacklistService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void blacklist(String accessToken, long remainingSeconds) {
        if (remainingSeconds <= 0) {
            return;
        }
        redis.opsForValue().set(PREFIX + accessToken, "1", Duration.ofSeconds(remainingSeconds));
    }

    public boolean isBlacklisted(String accessToken) {
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + accessToken));
    }
}
