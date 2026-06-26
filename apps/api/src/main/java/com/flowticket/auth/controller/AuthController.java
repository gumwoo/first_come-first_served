package com.flowticket.auth.controller;

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
import com.flowticket.global.security.JwtProvider;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 인증/가입 엔드포인트. 비즈니스 로직 없음, try/catch 없음(전역 핸들러). */
@RestController
public class AuthController {

    private final AuthService authService;
    private final PhoneVerificationService phoneVerificationService;
    private final TokenService tokenService;
    private final TokenBlacklistService blacklistService;
    private final JwtProvider jwtProvider;

    public AuthController(AuthService authService, PhoneVerificationService phoneVerificationService,
                          TokenService tokenService, TokenBlacklistService blacklistService,
                          JwtProvider jwtProvider) {
        this.authService = authService;
        this.phoneVerificationService = phoneVerificationService;
        this.tokenService = tokenService;
        this.blacklistService = blacklistService;
        this.jwtProvider = jwtProvider;
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
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req));
    }

    @PostMapping("/auth/refresh")
    public ApiResponse<TokenResponse> refresh(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(tokenService.rotate(body.get("refreshToken")));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal Long userId,
                                    @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (userId != null) {
            tokenService.revoke(userId);
        }
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String access = authorization.substring(7);
            blacklistService.blacklist(access, jwtProvider.getRemainingSeconds(access));
        }
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(authService.me(userId));
    }
}
