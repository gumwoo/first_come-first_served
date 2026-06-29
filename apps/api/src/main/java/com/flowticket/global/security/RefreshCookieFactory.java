package com.flowticket.global.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Refresh 토큰 httpOnly 쿠키 생성. 로컬 로그인/소셜 로그인 공용. */
@Component
public class RefreshCookieFactory {

    public static final String COOKIE_NAME = "refreshToken";

    private final long refreshTtlSeconds;
    private final boolean secure;

    public RefreshCookieFactory(@Value("${jwt.refresh-token-ttl}") long refreshTtlSeconds,
                                @Value("${app.cookie.secure:false}") boolean secure) {
        this.refreshTtlSeconds = refreshTtlSeconds;
        this.secure = secure; // 운영(HTTPS)은 true
    }

    public ResponseCookie create(String value, boolean remember) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/");
        if (remember) {
            b.maxAge(refreshTtlSeconds); // 영속 쿠키
        }
        return b.build();
    }

    public ResponseCookie expired() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(0).build();
    }
}
