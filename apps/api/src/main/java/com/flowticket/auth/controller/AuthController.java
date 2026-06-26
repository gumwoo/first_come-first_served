package com.flowticket.auth.controller;

import com.flowticket.auth.dto.AccessResponse;
import com.flowticket.auth.dto.LoginRequest;
import com.flowticket.auth.dto.MeResponse;
import com.flowticket.auth.dto.PhoneRequest;
import com.flowticket.auth.dto.PhoneVerifyRequest;
import com.flowticket.auth.dto.SignupRequest;
import com.flowticket.auth.dto.TokenResponse;
import com.flowticket.auth.service.AuthService;
import com.flowticket.auth.service.PhoneVerificationService;
import com.flowticket.auth.service.TokenBlacklistService;
import com.flowticket.auth.service.TokenService;
import com.flowticket.global.common.ApiResponse;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.global.security.JwtProvider;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 인증/가입 엔드포인트. Refresh는 httpOnly 쿠키. 비즈니스 로직·try/catch 없음. */
@RestController
public class AuthController {

    private static final String REFRESH_COOKIE = "refreshToken";

    private final AuthService authService;
    private final PhoneVerificationService phoneVerificationService;
    private final TokenService tokenService;
    private final TokenBlacklistService blacklistService;
    private final JwtProvider jwtProvider;
    private final long refreshTtlSeconds;

    public AuthController(AuthService authService, PhoneVerificationService phoneVerificationService,
                          TokenService tokenService, TokenBlacklistService blacklistService,
                          JwtProvider jwtProvider,
                          @Value("${jwt.refresh-token-ttl}") long refreshTtlSeconds) {
        this.authService = authService;
        this.phoneVerificationService = phoneVerificationService;
        this.tokenService = tokenService;
        this.blacklistService = blacklistService;
        this.jwtProvider = jwtProvider;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    @PostMapping("/auth/phone/request")
    public ApiResponse<Void> requestPhoneCode(@Valid @RequestBody PhoneRequest req) {
        phoneVerificationService.requestCode(req.phone());
        return ApiResponse.ok(null);
    }

    @PostMapping("/auth/phone/verify")
    public ApiResponse<Void> verifyPhoneCode(@Valid @RequestBody PhoneVerifyRequest req) {
        phoneVerificationService.verifyCode(req.phone(), req.code());
        return ApiResponse.ok(null);
    }

    @PostMapping("/auth/signup")
    public ApiResponse<Void> signup(@Valid @RequestBody SignupRequest req) {
        authService.signup(req);
        return ApiResponse.ok(null);
    }

    @PostMapping("/auth/login")
    public org.springframework.http.ResponseEntity<ApiResponse<AccessResponse>> login(
            @Valid @RequestBody LoginRequest req) {
        TokenResponse tokens = authService.login(req);
        return org.springframework.http.ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken(), req.remember()).toString())
                .body(ApiResponse.ok(new AccessResponse(tokens.accessToken())));
    }

    @PostMapping("/auth/refresh")
    public org.springframework.http.ResponseEntity<ApiResponse<AccessResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        TokenResponse tokens = tokenService.rotate(refreshToken);
        // 회전된 Refresh의 remember 플래그를 그대로 따라 쿠키 maxAge 결정(세션/영속 보존).
        boolean remember = jwtProvider.isRemember(tokens.refreshToken());
        return org.springframework.http.ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken(), remember).toString())
                .body(ApiResponse.ok(new AccessResponse(tokens.accessToken())));
    }

    @PostMapping("/auth/logout")
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal Long userId,
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        // access가 없어도(만료/미보유) refresh 쿠키로 사용자를 식별해 서버 Refresh를 폐기.
        Long target = userId;
        if (target == null && refreshToken != null
                && jwtProvider.isValid(refreshToken, JwtProvider.TYPE_REFRESH)) {
            target = jwtProvider.getUserId(refreshToken);
        }
        if (target != null) {
            tokenService.revoke(target);
        }
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String access = authorization.substring(7);
            blacklistService.blacklist(access, jwtProvider.getRemainingSeconds(access));
        }
        return org.springframework.http.ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(ApiResponse.ok(null));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(authService.me(userId));
    }

    private ResponseCookie refreshCookie(String value, boolean remember) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(false)        // 데모(http). 운영은 true + SameSite=None
                .sameSite("Lax")
                .path("/");
        if (remember) {
            b.maxAge(refreshTtlSeconds); // 영구 쿠키
        }
        // 미체크 시 maxAge 미설정 → 세션 쿠키
        return b.build();
    }

    private ResponseCookie expiredRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true).secure(false).sameSite("Lax").path("/").maxAge(0).build();
    }
}
