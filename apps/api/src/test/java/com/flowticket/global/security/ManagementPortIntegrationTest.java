package com.flowticket.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 운영 구성(관리 포트 분리, TS-041)을 실제 서버 두 개로 띄워 확인한다.
 *
 * 메인 포트(8080)는 Next rewrite로 인터넷에서 닿으므로 actuator가 없어야 하고 — 인코딩 변형(/%61ctuator)도 마찬가지 —,
 * K8s probe가 쓰는 /livez·/readyz는 메인 포트에 있어야 한다(관리 포트로 옮기면 메인 포트 포화를 놓친다).
 * Prometheus는 관리 포트에서 인증 없이 긁고, metrics처럼 인증이 필요한 actuator는 관리 포트에서도 막혀 있어야 한다.
 *
 * @AutoConfigureObservability: @SpringBootTest는 기본으로 메트릭 내보내기를 꺼서(management.defaults.metrics.export.enabled=false)
 * prometheus 엔드포인트 자체가 생기지 않는다. 그러면 "메인 포트 404"가 분리와 무관하게 통과해 버린다 — 켜야 단언이 의미가 있다.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class ManagementPortIntegrationTest extends IntegrationTestSupport {

    @LocalServerPort
    int serverPort;

    @LocalManagementPort
    int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(int port, String rawPath) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void 관리_포트가_메인_포트와_다르다() {
        assertThat(managementPort).isNotEqualTo(serverPort);
    }

    @Test
    void 메인_포트에는_prometheus가_없다_인코딩_변형_포함() throws Exception {
        assertThat(get(serverPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
        assertThat(get(serverPort, "/%61ctuator/prometheus").statusCode()).isEqualTo(404);
    }

    @Test
    void probe_추가_경로는_메인_포트에서_인증_없이_열린다() throws Exception {
        assertThat(get(serverPort, "/livez").statusCode()).isEqualTo(200);
        assertThat(get(serverPort, "/readyz").statusCode()).isEqualTo(200);
    }

    @Test
    void 관리_포트에서_prometheus를_인증_없이_긁는다() throws Exception {
        HttpResponse<String> res = get(managementPort, "/actuator/prometheus");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("jvm_memory_used_bytes");
    }

    @Test
    void 관리_포트에서도_metrics는_인증이_필요하다() throws Exception {
        assertThat(get(managementPort, "/actuator/metrics").statusCode()).isEqualTo(401);
    }
}
