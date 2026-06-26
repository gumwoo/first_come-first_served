package com.flowticket.global.security;

import com.flowticket.auth.service.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bearer Access Token 검증 + 블랙리스트 확인 후 인증 컨텍스트 설정. */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final TokenBlacklistService blacklist;

    public JwtAuthenticationFilter(JwtProvider jwtProvider, TokenBlacklistService blacklist) {
        this.jwtProvider = jwtProvider;
        this.blacklist = blacklist;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && jwtProvider.isValid(token) && !blacklist.isBlacklisted(token)) {
            Claims claims = jwtProvider.parse(token);
            String role = claims.get("role", String.class);
            var auth = new UsernamePasswordAuthenticationToken(
                    Long.valueOf(claims.getSubject()),
                    null,
                    List.of(new SimpleGrantedAuthority(role)));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
