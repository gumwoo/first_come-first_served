package com.flowticket.auth.bootstrap;

import com.flowticket.auth.domain.AuthProvider;
import com.flowticket.auth.domain.User;
import com.flowticket.auth.domain.UserRole;
import com.flowticket.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 관리자 계정 부트스트랩(S07). 가입 API는 ROLE_USER만 강제하므로, 관리자는 여기서 생성한다.
 * 자격증명은 환경변수(ADMIN_EMAIL / ADMIN_PASSWORD)로만 주입 — 코드·설정에 하드코딩하지 않는다.
 * 둘 다 설정됐고 해당 이메일 계정이 없을 때만 1회 생성(멱등). 미설정 시(테스트/키 없는 환경) no-op.
 */
@Component
public class AdminBootstrap implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder,
                          @Value("${admin.email:}") String email,
                          @Value("${admin.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(String... args) {
        if (email.isBlank() || password.isBlank()) {
            return; // 미설정 → 생성 안 함
        }
        if (userRepository.existsByEmail(email)) {
            return; // 이미 존재 → 멱등 skip
        }
        userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .name("관리자")
                .role(UserRole.ROLE_ADMIN)
                .provider(AuthProvider.local)
                .marketingOptIn(false)
                .build());
    }
}
