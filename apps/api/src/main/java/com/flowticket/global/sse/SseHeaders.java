package com.flowticket.global.sse;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

/**
 * SSE 응답이 중간 프록시에서 압축·버퍼링되지 않게 한다(TS-052).
 *
 * 브라우저 → ALB → Next(web, /api rewrite) → api 경로에서 Next 서버는 응답을 gzip으로 압축한다(compress 기본값).
 * SSE 프레임은 작고 드문드문 나가므로 압축기 버퍼에 쌓인 채 브라우저에 닿지 않았다 — 사이트 경유 연결은 12초 동안
 * 0바이트였고, 같은 경로에 Accept-Encoding: identity를 보내면 결제 0.63초 뒤 event:order.paid가 도착했다(측정 세션
 * 20261015-nok). Next가 쓰는 compression 미들웨어는 Cache-Control에 no-transform이 있으면 압축하지 않는다.
 * 사이트 전체 압축을 끄지 않고(next.config compress:false) SSE 응답만 비켜 가게 하려고 여기서 헤더를 단다.
 */
public final class SseHeaders {

    /**
     * no-transform: 프록시가 본문을 바꾸지(압축하지) 않게. no-cache, no-store: 수정 전 Security 기본값에서 가져와 유지한다 —
     * 여기서 Cache-Control을 먼저 달면 Security가 자기 캐시 헤더를 쓰지 않으므로 저장 금지를 직접 명시한다.
     */
    public static final String CACHE_CONTROL = "no-cache, no-store, no-transform";

    private SseHeaders() {
    }

    public static void preventProxyBuffering(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    }
}
