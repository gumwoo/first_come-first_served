package com.flowticket.auth.oauth;

import com.flowticket.auth.domain.AuthProvider;
import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/** 소셜 provider별 사용자 속성(email/name) 정규화. */
public record OAuth2Attributes(String email, String name) {

    @SuppressWarnings("unchecked")
    public static OAuth2Attributes of(String registrationId, Map<String, Object> attributes) {
        AuthProvider provider = AuthProvider.valueOf(registrationId);
        return switch (provider) {
            case naver -> {
                Map<String, Object> response = (Map<String, Object>) attributes.get("response");
                if (response == null) {
                    throw new OAuth2AuthenticationException(new OAuth2Error("invalid_response"));
                }
                yield new OAuth2Attributes((String) response.get("email"), (String) response.get("name"));
            }
            case kakao -> {
                // 카카오: { id, kakao_account: { email, profile: { nickname } } }
                Map<String, Object> account = (Map<String, Object>) attributes.get("kakao_account");
                if (account == null) {
                    throw new OAuth2AuthenticationException(new OAuth2Error("invalid_response"));
                }
                Map<String, Object> profile = (Map<String, Object>) account.get("profile");
                String nickname = profile != null ? (String) profile.get("nickname") : null;
                String email = (String) account.get("email");
                if (email == null) {
                    // 이메일 미동의/미보유 → 식별 불가
                    throw new OAuth2AuthenticationException(new OAuth2Error("email_required"));
                }
                yield new OAuth2Attributes(email, nickname);
            }
            default -> throw new OAuth2AuthenticationException(new OAuth2Error("unsupported_provider"));
        };
    }
}
