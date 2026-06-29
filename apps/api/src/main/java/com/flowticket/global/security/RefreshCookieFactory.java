package com.flowticket.global.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Refresh 토큰 httpOnly 쿠키 생성. 로컬 로그인/소셜 로그인 공용. */
@Component
public class RefreshCookieFactory {

    public static final String COOKIE_NAME = "refreshToken";

    private final long refreshTtlSeconds;

    public RefreshCookieFactory(@Value("${jwt.refresh-token-ttl}") long refreshTtlSeconds) {
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public ResponseCookie create(String value, boolean remember) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(false)        // 데모(http). 운영은 true + SameSite=None
                .sameSite("Lax")
                .path("/");
        if (remember) {
            b.maxAge(refreshTtlSeconds); // 영속 쿠키
        }
        return b.build();
    }

    public ResponseCookie expired() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true).secure(false).sameSite("Lax").path("/").maxAge(0).build();
    }
}
