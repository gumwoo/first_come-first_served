package com.flowticket.auth.service;

import com.flowticket.auth.domain.User;
import com.flowticket.auth.dto.TokenResponse;
import com.flowticket.auth.repository.UserRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.global.security.JwtProvider;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Refresh Token Rotation.
 * - 로그인/재발급 시 사용자별 최신 Refresh 토큰 1개를 Redis에 저장.
 * - 재발급 시 저장값과 일치해야 회전. 폐기된 토큰이 다시 오면 탈취로 간주해 패밀리 삭제.
 */
@Service
public class TokenService {

    private static final String PREFIX = "refresh:";

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final StringRedisTemplate redis;
    private final long refreshTtlSeconds;

    public TokenService(JwtProvider jwtProvider, UserRepository userRepository,
                        StringRedisTemplate redis,
                        @org.springframework.beans.factory.annotation.Value("${jwt.refresh-token-ttl}") long refreshTtlSeconds) {
        this.jwtProvider = jwtProvider;
        this.userRepository = userRepository;
        this.redis = redis;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    /** 로그인 성공 등에서 새 토큰쌍 발급 + Refresh 저장. */
    public TokenResponse issue(User user) {
        String access = jwtProvider.createAccessToken(user);
        String refresh = jwtProvider.createRefreshToken(user);
        store(user.getId(), refresh);
        return new TokenResponse(access, refresh);
    }

    /** Refresh Token Rotation으로 재발급. */
    public TokenResponse rotate(String refreshToken) {
        Long userId;
        try {
            if (!jwtProvider.isValid(refreshToken)) {
                throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
            }
            userId = jwtProvider.getUserId(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        String stored = redis.opsForValue().get(PREFIX + userId);
        if (stored == null) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }
        if (!stored.equals(refreshToken)) {
            // 이미 회전되어 폐기된 토큰 재사용 → 탈취 가능성. 패밀리 전체 폐기.
            redis.delete(PREFIX + userId);
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REUSED);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        return issue(user);
    }

    /** 로그아웃: 사용자 Refresh 삭제. */
    public void revoke(Long userId) {
        redis.delete(PREFIX + userId);
    }

    private void store(Long userId, String refreshToken) {
        redis.opsForValue().set(PREFIX + userId, refreshToken, Duration.ofSeconds(refreshTtlSeconds));
    }
}
