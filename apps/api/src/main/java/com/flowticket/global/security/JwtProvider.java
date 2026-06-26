package com.flowticket.global.security;

import com.flowticket.auth.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Access/Refresh JWT 발급·검증. 서명키는 환경변수에서만 주입(하드코딩 금지). */
@Component
public class JwtProvider {

    private final String secret;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;
    private SecretKey key;

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-ttl}") long accessTtlSeconds,
            @Value("${jwt.refresh-token-ttl}") long refreshTtlSeconds) {
        this.secret = secret;
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    @PostConstruct
    void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(User user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTtlSeconds * 1000))
                .signWith(key)
                .compact();
    }

    /** Refresh는 식별용 jti를 담는다(저장소의 최신 값과 비교). */
    public String createRefreshToken(User user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshTtlSeconds * 1000))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getUserId(String token) {
        return Long.valueOf(parse(token).getSubject());
    }

    /** 토큰 남은 수명(초). 블랙리스트 TTL 산정용. */
    public long getRemainingSeconds(String token) {
        long diff = parse(token).getExpiration().getTime() - System.currentTimeMillis();
        return Math.max(diff / 1000, 0);
    }
}
