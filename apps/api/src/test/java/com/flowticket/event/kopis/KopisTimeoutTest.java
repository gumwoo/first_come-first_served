package com.flowticket.event.kopis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 외부 API가 <b>응답하지 않을 때</b> 우리 스레드가 풀려나는지 검증한다.
 *
 * <p>이 테스트가 없어서 위험이 보이지 않았다. KopisClient는 타임아웃을 지정하지 않은
 * RestClient를 썼는데, 이 이미지에는 Apache HttpClient5·Jetty·OkHttp가 없어 JDK HttpClient로
 * 떨어지고 그쪽은 타임아웃 기본값이 없다. 즉 KOPIS가 연결만 물고 응답을 주지 않으면
 * 톰캣 스레드가 <b>영원히</b> 묶인다. 스레드 풀이 마르면 이 엔드포인트뿐 아니라 API 전체가
 * 멎는데, readiness는 DB·Redis만 보므로 파드는 UP으로 남고 K8s가 빼주지도 않는다.
 *
 * <p>MockRestServiceServer로는 이걸 못 잡는다 — 실제 소켓이 아니라 요청을 가로채기 때문에
 * 타임아웃 설정 자체가 관여하지 않는다. 그래서 <b>받기만 하고 아무것도 쓰지 않는 소켓</b>을
 * 직접 띄운다.
 */
class KopisTimeoutTest {

    /** 연결은 받아주지만 응답을 절대 쓰지 않는 서버 — "느린 외부 API"를 흉내낸다. */
    private ServerSocket silentServer;
    private ExecutorService accepter;

    @BeforeEach
    void startSilentServer() throws IOException {
        silentServer = new ServerSocket(0);
        accepter = Executors.newSingleThreadExecutor();
        accepter.submit(() -> {
            while (!silentServer.isClosed()) {
                try {
                    Socket s = silentServer.accept();
                    // 응답을 쓰지 않는다. 소켓은 열어둔 채 방치 — read timeout이 유일한 탈출구다.
                    s.getInputStream().read();
                } catch (IOException ignored) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void stop() throws IOException {
        silentServer.close();
        accepter.shutdownNow();
    }

    private KopisClient clientPointingAtSilentServer() {
        String baseUrl = "http://localhost:" + silentServer.getLocalPort();
        KopisClientConfig config = new KopisClientConfig();
        return new KopisClient(
                config.kopisDetailClient(org.springframework.web.client.RestClient.builder(), baseUrl),
                config.kopisSyncClient(org.springframework.web.client.RestClient.builder(), baseUrl),
                "test-key");
    }

    @Test
    void 상세조회는_응답없는_외부API에_묶이지_않는다() {
        KopisClient client = clientPointingAtSilentServer();

        // read timeout 3초 + 여유. 타임아웃이 없으면 여기서 영원히 멈춰 테스트가 죽는다.
        Optional<KopisEventDetail> result = assertTimeoutPreemptively(
                Duration.ofSeconds(15),
                () -> client.fetchDetail("PF000001"),
                "응답 없는 외부 API에 스레드가 묶였다 — 타임아웃이 걸리지 않았다");

        // 던지지 않고 빈 값으로 degrade해야 한다(상세는 없어도 응답할 수 있다).
        assertThat(result).isEmpty();
    }

    @Test
    void 목록조회도_응답없는_외부API에_묶이지_않는다() {
        KopisClient client = clientPointingAtSilentServer();

        List<KopisEvent> result = assertTimeoutPreemptively(
                Duration.ofSeconds(25), // sync는 read timeout 10초라 여유를 더 준다
                () -> client.fetchList("20260101", "20260102", 1, 100),
                "응답 없는 외부 API에 동기화 스레드가 묶였다");

        assertThat(result).isEmpty();
    }
}
