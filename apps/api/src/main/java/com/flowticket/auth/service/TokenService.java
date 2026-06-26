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
 * Refresh Token Rotation (+ grace window).
 * - 사용자별 최신 Refresh 1개를 Redis에 저장하고, 재발급마다 회전한다.
 * - 직전 토큰은 grace 윈도(짧은 TTL) 동안 허용한다 → 멀티탭/동시 새로고침에서
 *   한 탭이 회전한 직후 다른 탭이 직전 토큰을 보내도 세션이 폭파되지 않는다.
 * - grace 밖의 폐기된 토큰이 들어오면 탈취로 간주해 패밀리를 폐기한다.
 */
@Service
public class TokenService {

    private static final String CURRENT = "refresh:";
    private static final String PREV = "refresh:prev:";
    private static final Duration GRACE = Duration.ofSeconds(60);

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
            if (!jwtProvider.isValid(refreshToken, JwtProvider.TYPE_REFRESH)) {
                throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
            }
            userId = jwtProvider.getUserId(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, e);
        }

        String current = redis.opsForValue().get(CURRENT + userId);
        if (current == null) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

        if (current.equals(refreshToken)) {
            // 정상 회전: 현재 → 직전(grace), 새 토큰 발급
            redis.opsForValue().set(PREV + userId, current, GRACE);
            return issue(user);
        }

        String prev = redis.opsForValue().get(PREV + userId);
        if (refreshToken.equals(prev)) {
            // grace 윈도 내 동시 요청(멀티탭) — 폭파하지 않고 현재 토큰을 그대로 유지하며
            // 새 Access만 발급한다(쿠키는 현재 refresh로 갱신).
            return new TokenResponse(jwtProvider.createAccessToken(user), current);
        }

        // grace 밖의 폐기 토큰 재사용 → 탈취 가능성. 패밀리 폐기.
        redis.delete(CURRENT + userId);
        redis.delete(PREV + userId);
        throw new BusinessException(ErrorCode.REFRESH_TOKEN_REUSED);
    }

    /** 로그아웃: 사용자 Refresh 폐기. */
    public void revoke(Long userId) {
        redis.delete(CURRENT + userId);
        redis.delete(PREV + userId);
    }

    private void store(Long userId, String refreshToken) {
        redis.opsForValue().set(CURRENT + userId, refreshToken, Duration.ofSeconds(refreshTtlSeconds));
    }
}
