package com.flowticket.foo.dto;

import java.time.LocalDateTime;

// 위반: 응답 DTO가 LocalDateTime을 노출하는데 오프셋 직렬화기가 없다.
// 타임존 없이 나가면 브라우저가 자기 로컬 시간으로 해석해 서버-클라이언트 시차만큼 어긋난다.
public record FooResponse(Long id, LocalDateTime expiresAt) {}
