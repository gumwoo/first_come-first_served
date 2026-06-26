package com.flowticket.foo;

import io.jsonwebtoken.Jwts;

// VIOLATION: JWT를 만들면서 type claim을 넣지 않음 → 하네스가 실패해야 함
public class TokenThing {
    public String create(Object key) {
        return Jwts.builder()
                .subject("1")
                .claim("role", "ROLE_USER")
                .compact();
    }
}
