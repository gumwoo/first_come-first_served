package com.flowticket.auth.oauth;

import com.flowticket.auth.domain.AuthProvider;
import com.flowticket.auth.domain.User;
import com.flowticket.auth.repository.UserRepository;
import java.util.Set;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소셜 로그인 사용자 처리: provider 프로필을 받아 우리 users에 upsert.
 * - 신규: 소셜 계정 생성(passwordHash/phone null, provider=소셜, ROLE_USER)
 * - 기존 동일 provider: 그대로 사용
 * - 기존이 다른 provider(예: 로컬): 계정 연결 미지원 정책 → 차단
 */
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    public CustomOAuth2UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User oauthUser = super.loadUser(request);
        String registrationId = request.getClientRegistration().getRegistrationId();
        OAuth2Attributes attrs = OAuth2Attributes.of(registrationId, oauthUser.getAttributes());
        AuthProvider provider = AuthProvider.valueOf(registrationId);

        userRepository.findByEmail(attrs.email()).ifPresentOrElse(existing -> {
            if (existing.getProvider() != provider) {
                // 동일 이메일이 다른 방식으로 이미 가입됨 → 계정 연결 미지원
                throw new OAuth2AuthenticationException(new OAuth2Error("email_already_registered"));
            }
        }, () -> userRepository.save(User.social(attrs.email(), attrs.name(), provider)));

        String nameAttributeKey = request.getClientRegistration()
                .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();
        return new DefaultOAuth2User(
                Set.of(new SimpleGrantedAuthority("ROLE_USER")),
                oauthUser.getAttributes(),
                nameAttributeKey);
    }
}
