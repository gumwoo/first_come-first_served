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
    private final long sseTicketTtlSeconds;
    private SecretKey key;

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-ttl}") long accessTtlSeconds,
            @Value("${jwt.refresh-token-ttl}") long refreshTtlSeconds,
            @Value("${jwt.sse-ticket-ttl:300}") long sseTicketTtlSeconds) {
        this.secret = secret;
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
        this.sseTicketTtlSeconds = sseTicketTtlSeconds;
    }

    @PostConstruct
    void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    /**
     * SSE 구독 전용 티켓. {@code EventSource}는 요청 헤더를 붙일 수 없어 자격증명을 URL로 실어야
     * 하는데, access token을 그대로 URL에 두면 접근 로그·리퍼러에 <b>전체 권한 자격증명</b>이 남는다.
     * 그래서 <b>단일 주문 구독에만 쓸 수 있는</b> 별도 타입을 만든다 — {@link #isValid}가 type을
     * 대조하므로 이 티켓으로는 API를 호출할 수 없고, access token으로는 구독할 수 없다.
     */
    public static final String TYPE_SSE = "sse";

    public String createAccessToken(User user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("type", TYPE_ACCESS)
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTtlSeconds * 1000))
                .signWith(key)
                .compact();
    }

    /**
     * Refresh는 식별용 jti와 remember 플래그를 담는다.
     * remember는 회전 시 쿠키 maxAge(영속/세션)를 보존하기 위함.
     */
    public String createRefreshToken(User user, boolean remember) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .id(UUID.randomUUID().toString())
                .claim("type", TYPE_REFRESH)
                .claim("remember", remember)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshTtlSeconds * 1000))
                .signWith(key)
                .compact();
    }

    /** Refresh 토큰의 remember 플래그. */
    public boolean isRemember(String refreshToken) {
        Boolean v = parse(refreshToken).get("remember", Boolean.class);
        return Boolean.TRUE.equals(v);
    }

    /**
     * 특정 주문 구독에만 유효한 티켓. 대상 주문을 클레임에 박아, 티켓이 새더라도 <b>그 주문
     * 하나만</b> 열린다. TTL은 짧게 둔다({@code jwt.sse-ticket-ttl}, 기본 300초) — URL에 실리는
     * 자격증명이라 노출 창을 줄이는 것이 목적이고, 만료 후에는 프론트가 새로 발급받는다.
     *
     * <p>⚠️ <b>TTL은 "새 연결을 시작할 수 있는 기간"이지, 이미 성립한 스트림의 수명이 아니다.</b>
     * 검증은 구독 성립 시점에만 돌고, 만료가 기존 연결을 끊지 않는다. 성립한 스트림의 수명은
     * {@code seat.sse-timeout-ms}(기본 30분)가 정한다 — 티켓 TTL의 6배다. 그러므로 이 값을
     * "노출 상한"으로 읽으면 안 된다.
     */
    public String createSseTicket(Long userId, Long orderId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", TYPE_SSE)
                .claim("orderId", orderId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + sseTicketTtlSeconds * 1000))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /** 토큰이 유효하고 기대한 type(access/refresh)인지 검증. */
    public boolean isValid(String token, String expectedType) {
        try {
            Claims claims = parse(token);
            return expectedType.equals(claims.get("type", String.class));
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
