package com.flowticket.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * X-Forwarded-For 신뢰 경계 검증. XFF는 위조 가능하므로 신뢰 프록시가 아닌 peer의
 * XFF는 무시해야 조회수 dedup 우회(조회수 부풀리기)를 막는다.
 */
class ClientIpResolverTest {

    @Test
    void 신뢰프록시가_아니면_XFF를_무시하고_remoteAddr를_쓴다() {
        var resolver = new ClientIpResolver(""); // 신뢰 목록 없음(기본)
        assertThat(resolver.resolve("203.0.113.7", "1.2.3.4")).isEqualTo("203.0.113.7");
    }

    @Test
    void 신뢰프록시가_전달한_XFF는_사용한다() {
        var resolver = new ClientIpResolver("10.0.0.9"); // LB IP 신뢰
        assertThat(resolver.resolve("10.0.0.9", "1.2.3.4, 10.0.0.9")).isEqualTo("1.2.3.4");
    }

    @Test
    void XFF가_없으면_remoteAddr() {
        var resolver = new ClientIpResolver("10.0.0.9");
        assertThat(resolver.resolve("10.0.0.9", null)).isEqualTo("10.0.0.9");
    }

    @Test
    void 위조_XFF_100종은_같은_peer면_IP_1개로_수렴한다() {
        // 공격자가 매 요청 다른 XFF를 위조해도(직접 peer는 동일), 신뢰 목록에 없으면
        // 전부 remoteAddr로 수렴 → dedup 1건. (before: XFF-keyed였다면 100건으로 우회)
        var resolver = new ClientIpResolver("");
        long unique = IntStream.range(0, 100)
                .mapToObj(i -> resolver.resolve("203.0.113.7", "9.9.9." + i))
                .collect(Collectors.toSet()).size();
        assertThat(unique).isEqualTo(1);
    }

    @Test
    void 신뢰프록시_뒤에서는_100종_XFF가_100개로_구분된다() {
        // 정상 프록시가 전달하는 실제 클라이언트 IP는 그대로 구분되어야 한다(오탐 없음).
        var resolver = new ClientIpResolver("10.0.0.9");
        long unique = IntStream.range(0, 100)
                .mapToObj(i -> resolver.resolve("10.0.0.9", "9.9.9." + i))
                .collect(Collectors.toSet()).size();
        assertThat(unique).isEqualTo(100);
    }
}
