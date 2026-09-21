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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserRegistrar userRegistrar;
    private final TokenService tokenService;
    private final TokenBlacklistService blacklistService;
    private final JwtProvider jwtProvider;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       UserRegistrar userRegistrar, TokenService tokenService,
                       TokenBlacklistService blacklistService, JwtProvider jwtProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userRegistrar = userRegistrar;
        this.tokenService = tokenService;
        this.blacklistService = blacklistService;
        this.jwtProvider = jwtProvider;
    }

    /**
     * 회원가입. 이메일·휴대폰 중복은 동시 가입이어도 409로 돌려준다.
     *
     * 사전 검사를 통과한 동시 요청은 UNIQUE 위반으로 끝나고, 어느 제약인지는 다시 조회해 가른다.
     * 가입 트랜잭션은 UserRegistrar가 연다. NOT_SUPPORTED는 호출자가 트랜잭션을 들고 있어도
     * 이 캐치가 그 트랜잭션 안으로 들어가지 않게 한다(domain/auth.md §1, TS-014).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void signup(SignupRequest req) {
        try {
            userRegistrar.register(req);
        } catch (DataIntegrityViolationException e) {
            throw duplicateOf(req, e);
        }
    }

    /**
     * 제약 위반의 정체를 밝힌다. 우리가 아는 두 UNIQUE가 아니면 원 예외를 그대로 올린다.
     * NOT NULL·FK 위반까지 409로 뭉뚱그리면 진짜 버그가 정상 응답으로 숨는다.
     */
    private RuntimeException duplicateOf(SignupRequest req, DataIntegrityViolationException e) {
        if (userRepository.existsByEmail(req.email())) {
            return new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        if (userRepository.existsByPhone(req.phone())) {
            return new BusinessException(ErrorCode.DUPLICATE_PHONE);
        }
        return e;
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
