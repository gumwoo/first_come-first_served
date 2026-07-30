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
import com.flowticket.auth.service.TokenService;
import com.flowticket.global.common.ApiResponse;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.global.security.JwtProvider;
import com.flowticket.global.security.RefreshCookieFactory;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
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

    private static final String REFRESH_COOKIE = RefreshCookieFactory.COOKIE_NAME;

    private final AuthService authService;
    private final PhoneVerificationService phoneVerificationService;
    private final TokenService tokenService;
    private final JwtProvider jwtProvider;
    private final RefreshCookieFactory cookieFactory;

    public AuthController(AuthService authService, PhoneVerificationService phoneVerificationService,
                          TokenService tokenService, JwtProvider jwtProvider,
                          RefreshCookieFactory cookieFactory) {
        this.authService = authService;
        this.phoneVerificationService = phoneVerificationService;
        this.tokenService = tokenService;
        this.jwtProvider = jwtProvider;
        this.cookieFactory = cookieFactory;
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
                .header(HttpHeaders.SET_COOKIE, cookieFactory.create(tokens.refreshToken(), req.remember()).toString())
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
                .header(HttpHeaders.SET_COOKIE, cookieFactory.create(tokens.refreshToken(), remember).toString())
                .body(ApiResponse.ok(new AccessResponse(tokens.accessToken())));
    }

    @PostMapping("/auth/logout")
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal Long userId,
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        // HTTP 파싱만 컨트롤러 몫: "Bearer " 접두어 제거 → 토큰 오케스트레이션은 서비스에 위임.
        String accessToken = (authorization != null && authorization.startsWith("Bearer "))
                ? authorization.substring(7)
                : null;
        authService.logout(userId, refreshToken, accessToken);
        return org.springframework.http.ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.expired().toString())
                .body(ApiResponse.ok(null));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(authService.me(userId));
    }
}
