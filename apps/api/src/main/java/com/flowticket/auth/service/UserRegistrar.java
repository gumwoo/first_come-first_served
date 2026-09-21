package com.flowticket.auth.service;

import com.flowticket.auth.domain.AuthProvider;
import com.flowticket.auth.domain.User;
import com.flowticket.auth.domain.UserRole;
import com.flowticket.auth.dto.SignupRequest;
import com.flowticket.auth.repository.UserRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입 트랜잭션. 쓰기 전용 협력자다.
 *
 * AuthService 안에 두면 signup()이 자기 메서드를 불러 @Transactional이 적용되지 않는다. 그렇다고
 * 프록시를 스스로 주입받으면(ObjectProvider&lt;AuthService&gt;) 트랜잭션 경계가 서비스의 의존성으로
 * 드러난다. 별도 빈으로 나누면 경계가 호출 관계로 표현되고, 테스트도 프록시 없이 구성된다.
 *
 * 경계를 나누는 이유는 UNIQUE 위반을 잡는 위치 때문이다. 사전 검사를 통과한 동시 가입은 커밋에서
 * 제약에 걸리는데, 그 예외는 트랜잭션이 끝난 뒤에 잡아야 한다(domain/auth.md §1, TS-014).
 */
@Component
public class UserRegistrar {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PhoneVerificationService phoneVerificationService;

    public UserRegistrar(UserRepository userRepository, PasswordEncoder passwordEncoder,
                         PhoneVerificationService phoneVerificationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.phoneVerificationService = phoneVerificationService;
    }

    /** 인증선행 → 약관 → 중복 → 해시 → ROLE_USER 강제 → 저장 → 플래그 소비. */
    @Transactional
    public void register(SignupRequest req) {
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
        // flush를 앞당긴다: 커밋 시점까지 미루면 제약 위반이 나기 전에 아래 소비가 먼저 실행돼,
        // 경쟁에서 진 쪽이 휴대폰 인증 플래그까지 잃고 재시도하려면 인증을 다시 받아야 한다.
        userRepository.saveAndFlush(user);
        phoneVerificationService.consumeVerification(req.phone());
    }
}
