package com.flowticket.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Claims;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 부하 시험용 토큰 발급기(infra/loadgen/mint-tokens.mjs)와 앱의 JWT 검증 사이의 계약.
 *
 * 부하 시험은 로그인(BCrypt) 없이 서명 키로 토큰을 직접 만든다(loadtest-100k-plan §2.4 ①). 그 형식이 앱과
 * 어긋나면 진입 요청이 전부 401로 떨어지고, 그게 측정 세션에 가서야 드러난다. 그래서 발급기가 만든 토큰
 * (fixture, 같은 입력이면 같은 출력)을 앱의 검증기가 그대로 받아들이는지 여기서 고정한다.
 * 발급기 쪽에서는 infra/loadgen/mint-tokens.test.mjs가 같은 입력으로 이 fixture를 다시 만들어 대조한다.
 *
 * 키는 실제 서명 키가 아니라 이 계약에만 쓰는 고정값이다.
 */
class LoadgenTokenContractTest {

    private static final String CONTRACT_KEY = "loadgen-contract-test-key-not-a-real-secret-0123456789-abcdefgh";

    private JwtProvider provider;
    private String token;

    @BeforeEach
    void setUp() throws IOException {
        provider = new JwtProvider(CONTRACT_KEY, 1800, 1209600, 300);
        provider.init();
        try (InputStream in = getClass().getResourceAsStream("/loadgen/contract-token.txt")) {
            token = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    @Test
    void 발급기가_만든_토큰을_access로_받아들인다() {
        assertThat(provider.isValid(token, JwtProvider.TYPE_ACCESS)).isTrue();
    }

    @Test
    void 사용자와_권한을_앱과_같은_클레임으로_읽는다() {
        // 인증 필터는 sub를 사용자 ID로, role 클레임을 권한으로 쓴다(JwtAuthenticationFilter).
        Claims claims = provider.parse(token);
        assertThat(provider.getUserId(token)).isEqualTo(42L);
        assertThat(claims.get("role", String.class)).isEqualTo("ROLE_USER");
        assertThat(claims.get("email", String.class)).isEqualTo("loadseed+42@example.com");
    }

    @Test
    void refresh로는_통과하지_않는다() {
        assertThat(provider.isValid(token, JwtProvider.TYPE_REFRESH)).isFalse();
    }

    /** 서명이 다른 키로 만들어졌으면 거부해야 한다 — 계약이 "아무 토큰이나 통과"로 무너지지 않았는지 본다. */
    @Test
    void 다른_키의_서명은_거부한다() {
        JwtProvider other = new JwtProvider(CONTRACT_KEY.replace('a', 'b'), 1800, 1209600, 300);
        other.init();

        assertThat(other.isValid(token, JwtProvider.TYPE_ACCESS)).isFalse();
    }
}
