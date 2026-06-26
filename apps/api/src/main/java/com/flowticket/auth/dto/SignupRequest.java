package com.flowticket.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 회원가입 요청. role은 받지 않는다(서버가 ROLE_USER 강제). */
public record SignupRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 64) String password,
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Pattern(regexp = "^01[0-9]{8,9}$") String phone,
        @AssertTrue(message = "필수 약관에 동의해야 합니다.") boolean termsAccepted
) {}
