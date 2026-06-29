package com.flowticket.auth.oauth;

import com.flowticket.auth.domain.User;
import com.flowticket.auth.dto.TokenResponse;
import com.flowticket.auth.repository.UserRepository;
import com.flowticket.auth.service.TokenService;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.global.security.RefreshCookieFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * 소셜 로그인 성공: 우리 JWT 발급 → Refresh는 httpOnly 쿠키, Access는 URL에 싣지 않고
 * 프론트로 리다이렉트. 프론트는 로드 시 /auth/refresh로 Access를 받는다(토큰 URL 비노출).
 */
@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final RefreshCookieFactory cookieFactory;
    private final String successRedirect;

    public OAuth2SuccessHandler(UserRepository userRepository, TokenService tokenService,
                                RefreshCookieFactory cookieFactory,
                                @Value("${app.oauth2.success-redirect}") String successRedirect) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.cookieFactory = cookieFactory;
        this.successRedirect = successRedirect;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String registrationId = token.getAuthorizedClientRegistrationId();
        OAuth2Attributes attrs = OAuth2Attributes.of(registrationId, token.getPrincipal().getAttributes());

        User user = userRepository.findByEmail(attrs.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        // 소셜 로그인은 영속 세션으로 취급(remember=true)
        TokenResponse tokens = tokenService.issue(user, true);
        response.addHeader(HttpHeaders.SET_COOKIE, cookieFactory.create(tokens.refreshToken(), true).toString());

        // 프론트가 "소셜 로그인으로 복원됨"을 인지해 탭 동기화 신호를 쏘도록 표식 부착
        String target = successRedirect + (successRedirect.contains("?") ? "&" : "?") + "login=social";
        getRedirectStrategy().sendRedirect(request, response, target);
    }
}
