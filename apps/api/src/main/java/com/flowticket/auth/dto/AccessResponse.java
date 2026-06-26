package com.flowticket.auth.dto;

/** 로그인/재발급 응답 본문. Refresh는 httpOnly 쿠키로 내려가고 본문엔 Access만. */
public record AccessResponse(String accessToken) {}
