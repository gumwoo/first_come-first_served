package com.flowticket.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.flowticket.auth.domain.AuthProvider;
import com.flowticket.auth.domain.User;
import com.flowticket.auth.domain.UserRole;
import com.flowticket.auth.dto.LoginRequest;
import com.flowticket.auth.dto.SignupRequest;
import com.flowticket.auth.repository.UserRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock PhoneVerificationService phoneVerificationService;
    @Mock TokenService tokenService;
    @InjectMocks AuthService authService;

    private SignupRequest signupReq() {
        return new SignupRequest("a@b.com", "password1", "홍길동", "01012345678", true, false);
    }

    @Test
    void 가입_성공시_ROLE_USER로_저장된다() {
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByPhone(anyString())).thenReturn(false);

        authService.signup(signupReq());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.ROLE_USER);
        assertThat(captor.getValue().getProvider()).isEqualTo(AuthProvider.local);
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("HASH");
    }

    @Test
    void 휴대폰_미인증이면_가입_거절() {
        doThrow(new BusinessException(ErrorCode.PHONE_VERIFICATION_REQUIRED))
                .when(phoneVerificationService).assertVerified(anyString());
        assertThatThrownBy(() -> authService.signup(signupReq()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PHONE_VERIFICATION_REQUIRED);
    }

    @Test
    void 약관_미동의면_가입_거절() {
        SignupRequest req = new SignupRequest("a@b.com", "password1", "홍길동", "01012345678", false, false);
        assertThatThrownBy(() -> authService.signup(req))
                .extracting("errorCode").isEqualTo(ErrorCode.REGISTRATION_TERMS_NOT_ACCEPTED);
    }

    @Test
    void 이메일_중복이면_가입_거절() {
        when(userRepository.existsByEmail(anyString())).thenReturn(true);
        assertThatThrownBy(() -> authService.signup(signupReq()))
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    void 휴대폰_중복이면_가입_거절() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByPhone(anyString())).thenReturn(true);
        assertThatThrownBy(() -> authService.signup(signupReq()))
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_PHONE);
    }

    @Test
    void 소셜계정은_로컬로그인_불가() {
        User social = User.builder()
                .email("a@b.com").passwordHash(null).name("n").phone("01012345678")
                .role(UserRole.ROLE_USER).provider(AuthProvider.kakao).build();
        when(userRepository.findByEmail("a@b.com")).thenReturn(java.util.Optional.of(social));
        assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", "password1", false)))
                .extracting("errorCode").isEqualTo(ErrorCode.LOCAL_LOGIN_NOT_ALLOWED);
    }

    @Test
    void 비밀번호_불일치면_UNAUTHORIZED() {
        User user = User.builder()
                .email("a@b.com").passwordHash("HASH").name("n").phone("01012345678")
                .role(UserRole.ROLE_USER).provider(AuthProvider.local).build();
        when(userRepository.findByEmail("a@b.com")).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", "wrong", false)))
                .extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
