package com.flowticket.auth.service;

import com.flowticket.auth.domain.User;
import com.flowticket.auth.dto.TokenResponse;
import com.flowticket.auth.repository.UserRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.global.security.JwtProvider;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Refresh Token Rotation (+ grace window).
 * 회전 판정(current 비교 → prev 저장 → 새 토큰 저장)을 **Redis Lua 스크립트로 원자 실행**해
 * 동일 refresh로 동시에 들어온 요청도 직렬화한다(첫 요청만 회전, 나머지는 grace로 처리).
 */
@Service
public class TokenService {

    private static final String CURRENT = "refresh:";
    private static final String PREV = "refresh:prev:";
    private static final Duration GRACE = Duration.ofSeconds(60);

    // 반환 [status, token]: 0=만료, 1=회전(token=새토큰), 2=grace(token=현재토큰), 3=재사용
    private static final String ROTATE_LUA = """
            local current = redis.call('GET', KEYS[1])
            if not current then return {0, ''} end
            if current == ARGV[1] then
              redis.call('SET', KEYS[2], current, 'EX', tonumber(ARGV[4]))
              redis.call('SET', KEYS[1], ARGV[2], 'EX', tonumber(ARGV[3]))
              return {1, ARGV[2]}
            end
            local prev = redis.call('GET', KEYS[2])
            if prev and prev == ARGV[1] then
              return {2, current}
            end
            redis.call('DEL', KEYS[1])
            redis.call('DEL', KEYS[2])
            return {3, ''}
            """;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> ROTATE_SCRIPT =
            new DefaultRedisScript<>(ROTATE_LUA, List.class);

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

    /** 로그인 성공 등에서 새 토큰쌍 발급 + Refresh 저장. remember는 쿠키 maxAge 보존용. */
    public TokenResponse issue(User user, boolean remember) {
        String access = jwtProvider.createAccessToken(user);
        String refresh = jwtProvider.createRefreshToken(user, remember);
        redis.opsForValue().set(CURRENT + user.getId(), refresh, Duration.ofSeconds(refreshTtlSeconds));
        return new TokenResponse(access, refresh);
    }

    /** Refresh Token Rotation으로 재발급(원자적). */
    public TokenResponse rotate(String refreshToken) {
        Long userId;
        boolean remember;
        try {
            if (!jwtProvider.isValid(refreshToken, JwtProvider.TYPE_REFRESH)) {
                throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
            }
            userId = jwtProvider.getUserId(refreshToken);
            remember = jwtProvider.isRemember(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, e);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

        String newRefresh = jwtProvider.createRefreshToken(user, remember);
        List<?> result = redis.execute(ROTATE_SCRIPT,
                List.of(CURRENT + userId, PREV + userId),
                refreshToken, newRefresh,
                String.valueOf(refreshTtlSeconds), String.valueOf(GRACE.getSeconds()));

        long status = ((Number) result.get(0)).longValue();
        return switch ((int) status) {
            case 1 -> new TokenResponse(jwtProvider.createAccessToken(user), newRefresh);
            // grace: 현재 토큰 유지(쿠키도 현재값으로), 새 Access만 발급
            case 2 -> new TokenResponse(jwtProvider.createAccessToken(user), (String) result.get(1));
            case 3 -> throw new BusinessException(ErrorCode.REFRESH_TOKEN_REUSED);
            default -> throw new BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        };
    }

    /** 로그아웃: 사용자 Refresh 폐기. */
    public void revoke(Long userId) {
        redis.delete(CURRENT + userId);
        redis.delete(PREV + userId);
    }
}
