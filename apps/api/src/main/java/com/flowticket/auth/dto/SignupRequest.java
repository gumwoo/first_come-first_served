package com.flowticket.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 회원가입 요청. role은 받지 않는다(서버가 ROLE_USER 강제). */
public record SignupRequest(
        @NotBlank @Email String email,
        // 영문 + 숫자 + 특수문자 모두 포함, 8자 이상 (domain/auth.md 비밀번호 정책)
        @NotBlank
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,64}$",
                message = "비밀번호는 영문·숫자·특수문자를 모두 포함해 8자 이상이어야 합니다."
        )
        String password,
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Pattern(regexp = "^01[0-9]{8,9}$") String phone,
        @AssertTrue(message = "필수 약관에 동의해야 합니다.") boolean termsAccepted,
        boolean marketingOptIn
) {}
