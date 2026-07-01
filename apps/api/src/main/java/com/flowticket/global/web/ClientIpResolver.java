package com.flowticket.global.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 클라이언트 IP 해석. X-Forwarded-For는 클라이언트가 임의로 위조할 수 있으므로,
 * 직접 접속 peer(remoteAddr)가 신뢰 프록시 목록(app.proxy.trusted)에 있을 때만 XFF를 신뢰한다.
 * 목록이 비어 있으면(기본) 항상 remoteAddr를 사용해 위조를 무력화한다.
 * (조회수 dedup 기준 IP로 쓰이므로, 위조 시 dedup 우회로 조회수가 부풀 수 있음.)
 */
@Component
public class ClientIpResolver {

    private final Set<String> trustedProxies;

    public ClientIpResolver(@Value("${app.proxy.trusted:}") String csv) {
        this.trustedProxies = StringUtils.hasText(csv)
                ? Arrays.stream(csv.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet())
                : Set.of();
    }

    public String resolve(HttpServletRequest request) {
        return resolve(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
    }

    /** 신뢰 프록시가 전달한 XFF만 사용, 아니면 remoteAddr. 순수 메서드(테스트용). */
    String resolve(String remoteAddr, String xff) {
        if (StringUtils.hasText(xff) && trustedProxies.contains(remoteAddr)) {
            return xff.split(",")[0].trim(); // 신뢰 프록시가 세팅한 첫 홉(원 클라이언트)
        }
        return remoteAddr;
    }
}
