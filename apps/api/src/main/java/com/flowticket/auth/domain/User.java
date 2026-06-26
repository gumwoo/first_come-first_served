package com.flowticket.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    /** BCrypt 해시. 소셜 계정은 null. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    /** 로컬 가입은 필수, 소셜 가입은 null 가능. */
    @Column(unique = true, length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 이벤트/혜택 알림 수신 동의(선택 약관). */
    @Column(name = "marketing_opt_in", nullable = false)
    private boolean marketingOptIn;

    @Builder
    private User(String email, String passwordHash, String name, String phone,
                 UserRole role, AuthProvider provider, boolean marketingOptIn) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.phone = phone;
        this.role = role;
        this.provider = provider;
        this.marketingOptIn = marketingOptIn;
        this.createdAt = LocalDateTime.now();
    }

    /** 소셜 계정 여부 — passwordHash 격리 불변식 확인용. */
    public boolean isSocial() {
        return provider != AuthProvider.local;
    }

    /** 소셜 로그인 가입 계정 생성(휴대폰/비밀번호 없음, ROLE_USER 강제). */
    public static User social(String email, String name, AuthProvider provider) {
        return User.builder()
                .email(email)
                .name(name)
                .provider(provider)
                .role(UserRole.ROLE_USER)
                .build();
    }
}
