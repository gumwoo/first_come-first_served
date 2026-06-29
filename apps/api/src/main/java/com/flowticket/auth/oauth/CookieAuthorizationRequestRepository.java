package com.flowticket.auth.oauth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/**
 * STATELESS 환경의 OAuth2 인가요청 저장소.
 * - 쿠키에는 state 값만 담고, 실제 OAuth2AuthorizationRequest는 **서버 Redis**에 저장.
 *   (클라 쿠키에 직렬화 객체를 담아 역직렬화하던 방식 제거 → 신뢰 경계 안전)
 * - 인가 시작 ~ 콜백 사이(짧은 TTL)만 보존.
 */
@Component
public class CookieAuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String COOKIE_NAME = "oauth2_state";
    private static final String REDIS_PREFIX = "oauth:authreq:";
    private static final Duration TTL = Duration.ofMinutes(3);

    private final StringRedisTemplate redis;
    private final boolean secure;
    private final JdkSerializationRedisSerializer serializer = new JdkSerializationRedisSerializer();

    public CookieAuthorizationRequestRepository(StringRedisTemplate redis,
                                                @Value("${app.cookie.secure:false}") boolean secure) {
        this.redis = redis;
        this.secure = secure;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = getCookie(request);
        if (state == null) return null;
        String stored = redis.opsForValue().get(REDIS_PREFIX + state);
        if (stored == null) return null;
        return (OAuth2AuthorizationRequest) serializer.deserialize(Base64.getDecoder().decode(stored));
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            removeAuthorizationRequest(request, response);
            return;
        }
        String state = authorizationRequest.getState();
        String value = Base64.getEncoder().encodeToString(serializer.serialize(authorizationRequest));
        redis.opsForValue().set(REDIS_PREFIX + state, value, TTL);
        response.addHeader("Set-Cookie", stateCookie(state, TTL.getSeconds()).toString());
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest authRequest = loadAuthorizationRequest(request);
        String state = getCookie(request);
        if (state != null) {
            redis.delete(REDIS_PREFIX + state);
        }
        response.addHeader("Set-Cookie", stateCookie("", 0).toString());
        return authRequest;
    }

    private ResponseCookie stateCookie(String value, long maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private String getCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (COOKIE_NAME.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
