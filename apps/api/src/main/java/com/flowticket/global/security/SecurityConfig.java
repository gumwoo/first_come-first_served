package com.flowticket.global.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CSRF: 헤더(Bearer) 기반 API는 stateless라 CSRF 영향이 작다(무관은 아님).
                // 쿠키가 자동 전송되는 경로는 /auth/refresh·/auth/logout 뿐 → 이들은
                // POST-only + Refresh 쿠키 SameSite=Lax로 방어한다(SameSite는 완전한
                // 대체재가 아님 — OWASP). 운영 전환 시 쿠키 경로에 한해 SameSite=Strict
                // 또는 CSRF 토큰을 추가 검토한다(domain/auth.md).
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        // 인증 실패(미인증/블랙리스트) → 401, 공통 { error } 바디
                        .authenticationEntryPoint(
                                (req, res, ex) -> writeError(res, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHORIZED", "인증이 필요합니다."))
                        // 인가 실패(권한 부족) → 403
                        .accessDeniedHandler(
                                (req, res, ex) -> writeError(res, HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN", "권한이 없습니다.")))
                .authorizeHttpRequests(auth -> auth
                        // actuator는 health/info만 공개, metrics·prometheus 등은 인증 필요(정보 노출 방지)
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/auth/**", "/oauth2/**", "/sse/**",
                                "/events/**", "/search").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 인증/인가 실패도 공통 { error: { code, message } } 포맷으로 응답. */
    private static void writeError(HttpServletResponse res, int status, String code, String message)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
    }
}
