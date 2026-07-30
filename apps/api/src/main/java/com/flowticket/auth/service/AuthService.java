package com.flowticket.auth.service;

import com.flowticket.auth.domain.AuthProvider;
import com.flowticket.auth.domain.User;
import com.flowticket.auth.domain.UserRole;
import com.flowticket.auth.dto.LoginRequest;
import com.flowticket.auth.dto.MeResponse;
import com.flowticket.auth.dto.SignupRequest;
import com.flowticket.auth.dto.TokenResponse;
import com.flowticket.auth.repository.UserRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.global.security.JwtProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PhoneVerificationService phoneVerificationService;
    private final TokenService tokenService;
    private final TokenBlacklistService blacklistService;
    private final JwtProvider jwtProvider;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       PhoneVerificationService phoneVerificationService, TokenService tokenService,
                       TokenBlacklistService blacklistService, JwtProvider jwtProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.phoneVerificationService = phoneVerificationService;
        this.tokenService = tokenService;
        this.blacklistService = blacklistService;
        this.jwtProvider = jwtProvider;
    }

    /** 회원가입: 인증선행 → 약관 → 중복 → 해시 → ROLE_USER 강제 → 저장 → 플래그 소비. */
    @Transactional
    public void signup(SignupRequest req) {
        phoneVerificationService.assertVerified(req.phone());
        if (!req.termsAccepted()) {
            throw new BusinessException(ErrorCode.REGISTRATION_TERMS_NOT_ACCEPTED);
        }
        if (userRepository.existsByEmail(req.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        if (userRepository.existsByPhone(req.phone())) {
            throw new BusinessException(ErrorCode.DUPLICATE_PHONE);
        }
        User user = User.builder()
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .name(req.name())
                .phone(req.phone())
                .role(UserRole.ROLE_USER)        // 요청 값과 무관하게 강제
                .provider(AuthProvider.local)
                .marketingOptIn(req.marketingOptIn())
                .build();
        userRepository.save(user);
        phoneVerificationService.consumeVerification(req.phone());
    }

    /** 로그인: 소셜 계정은 로컬 로그인 불가, 비번 검증 후 토큰 발급. */
    @Transactional
    public TokenResponse login(LoginRequest req) {
        // 이메일 없음/비번 불일치를 구분하지 않음(account enumeration 방지) → INVALID_CREDENTIALS
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        if (user.isSocial() || user.getPasswordHash() == null) {
            throw new BusinessException(ErrorCode.LOCAL_LOGIN_NOT_ALLOWED);
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        return tokenService.issue(user, req.remember());
    }

    public MeResponse me(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        return MeResponse.from(user);
    }

    /**
     * 로그아웃 오케스트레이션. access가 없어도(만료/미보유) refresh로 사용자를 식별해 서버 Refresh를 폐기하고,
     * 유효한 access는 남은 TTL만큼 블랙리스트에 올린다. 쿠키/헤더 파싱·SET_COOKIE 같은 HTTP 요소는 컨트롤러 몫.
     *
     * @param userId       인증 컨텍스트의 사용자(access 유효 시 존재), 없으면 null
     * @param refreshToken refresh 쿠키 원문(없으면 null)
     * @param accessToken  "Bearer " 접두어를 제거한 access 원문(없으면 null)
     */
    public void logout(Long userId, String refreshToken, String accessToken) {
        Long target = userId;
        if (target == null && refreshToken != null
                && jwtProvider.isValid(refreshToken, JwtProvider.TYPE_REFRESH)) {
            target = jwtProvider.getUserId(refreshToken);
        }
        if (target != null) {
            tokenService.revoke(target);
        }
        // 깨진/위조 토큰이면 getRemainingSeconds가 예외→500이 되므로 유효할 때만 블랙리스트
        if (accessToken != null && jwtProvider.isValid(accessToken, JwtProvider.TYPE_ACCESS)) {
            blacklistService.blacklist(accessToken, jwtProvider.getRemainingSeconds(accessToken));
        }
    }
}
