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

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       PhoneVerificationService phoneVerificationService, TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.phoneVerificationService = phoneVerificationService;
        this.tokenService = tokenService;
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
                .build();
        userRepository.save(user);
        phoneVerificationService.consumeVerification(req.phone());
    }

    /** 로그인: 소셜 계정은 로컬 로그인 불가, 비번 검증 후 토큰 발급. */
    @Transactional
    public TokenResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (user.isSocial() || user.getPasswordHash() == null) {
            throw new BusinessException(ErrorCode.LOCAL_LOGIN_NOT_ALLOWED);
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return tokenService.issue(user);
    }

    public MeResponse me(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        return MeResponse.from(user);
    }
}
