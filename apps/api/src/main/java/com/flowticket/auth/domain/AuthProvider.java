package com.flowticket.auth.domain;

/** 가입 경로. local=이메일/비번, kakao/naver=소셜. */
public enum AuthProvider {
    local,
    kakao,
    naver
}
