package com.flowticket.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowticket.auth.domain.AuthProvider;
import com.flowticket.auth.domain.User;
import com.flowticket.auth.domain.UserRole;
import com.flowticket.auth.dto.LoginRequest;
import com.flowticket.auth.dto.SignupRequest;
import com.flowticket.auth.repository.UserRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.global.security.JwtProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock PhoneVerificationService phoneVerificationService;
    @Mock TokenService tokenService;
    @Mock TokenBlacklistService blacklistService;
    @Mock JwtProvider jwtProvider;
    @Mock ObjectProvider<AuthService> self;
    @InjectMocks AuthService authService;

    /**
     * signup()은 트랜잭션 경계를 나누려고 프록시 self-호출로 signupTx()를 부른다.
     * 단위 테스트에는 프록시가 없으니 자기 자신을 돌려준다(경계 자체는 통합 테스트의 몫).
     */
    @org.junit.jupiter.api.BeforeEach
    void wireSelf() {
        org.mockito.Mockito.lenient().when(self.getObject()).thenReturn(authService);
    }

    @Test
    void 저장이_UNIQUE에_걸리면_500이_아니라_중복_이메일로_변환된다() {
        // 사전 검사는 통과했는데(동시 가입) 커밋에서 uq_users_email에 걸린 상황.
        when(userRepository.existsByEmail(anyString())).thenReturn(false, true);
        when(userRepository.existsByPhone(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("uq_users_email"));

        assertThatThrownBy(() -> authService.signup(signupReq()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    void 우리가_아는_제약이_아니면_원_예외를_그대로_올린다() {
        // NOT NULL·FK 위반까지 409로 뭉뚱그리면 진짜 버그가 정상 응답으로 숨는다.
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByPhone(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("null value in column \"name\""));

        assertThatThrownBy(() -> authService.signup(signupReq()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

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
        org.mockito.Mockito.verify(userRepository).saveAndFlush(captor.capture());
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
    void 비밀번호_불일치면_INVALID_CREDENTIALS() {
        User user = User.builder()
                .email("a@b.com").passwordHash("HASH").name("n").phone("01012345678")
                .role(UserRole.ROLE_USER).provider(AuthProvider.local).build();
        when(userRepository.findByEmail("a@b.com")).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", "wrong", false)))
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    // --- logout: 컨트롤러에서 분리한 토큰 오케스트레이션(MVC 없이 분기 단위검증) ---

    @Test
    void 로그아웃_access가_유효하면_revoke와_블랙리스트를_수행한다() {
        when(jwtProvider.isValid("acc", JwtProvider.TYPE_ACCESS)).thenReturn(true);
        when(jwtProvider.getRemainingSeconds("acc")).thenReturn(300L);

        authService.logout(7L, null, "acc");

        verify(tokenService).revoke(7L);
        verify(blacklistService).blacklist("acc", 300L);
    }

    @Test
    void 로그아웃_access없이_refresh로_식별되면_refresh소유자를_revoke한다() {
        // access 만료/미보유 상황 — MVC로는 재현이 번거로운 분리 이득 분기
        when(jwtProvider.isValid("ref", JwtProvider.TYPE_REFRESH)).thenReturn(true);
        when(jwtProvider.getUserId("ref")).thenReturn(42L);

        authService.logout(null, "ref", null);

        verify(tokenService).revoke(42L);
        verifyNoInteractions(blacklistService); // access 없음 → 블랙리스트 안 함
    }

    @Test
    void 로그아웃_깨진_access는_블랙리스트하지_않는다() {
        // getRemainingSeconds가 예외→500이 되지 않도록 유효할 때만 블랙리스트
        when(jwtProvider.isValid("bad", JwtProvider.TYPE_ACCESS)).thenReturn(false);

        authService.logout(9L, null, "bad");

        verify(tokenService).revoke(9L);
        verify(jwtProvider, never()).getRemainingSeconds(anyString());
        verifyNoInteractions(blacklistService);
    }
}
