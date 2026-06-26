package com.flowticket.auth.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/** 소셜 로그인 실패: 프론트 로그인 페이지로 에러 표시와 함께 리다이렉트. */
@Component
public class OAuth2FailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final String failureRedirect;

    public OAuth2FailureHandler(@Value("${app.oauth2.failure-redirect}") String failureRedirect) {
        this.failureRedirect = failureRedirect;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        getRedirectStrategy().sendRedirect(request, response, failureRedirect);
    }
}
