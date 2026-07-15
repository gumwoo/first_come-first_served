package com.flowticket.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 공유 싱글톤 Testcontainers(TS-011). 모든 통합테스트가 postgres/redis 컨테이너 "한 쌍"을 재사용한다.
 * JVM 시작 시 1회만 기동(static init)하고, 종료 시 Testcontainers Ryuk가 정리한다.
 *
 * 배경: 통합테스트 클래스마다 @Container로 컨테이너 쌍을 따로 띄우면(N클래스 = N쌍) CI 러너 자원이
 * 고갈되고, 종료된 컨테이너에 스케줄러가 붙어 Redis command timeout이 나며 동시성 테스트가 간헐 실패했다.
 * 컨테이너를 공유하면 쌍이 1개로 줄어 자원 경합·타임아웃 플레이크가 사라진다. (테스트 간 데이터 격리는
 * 각 클래스의 @BeforeEach deleteAll이 담당 — 컨테이너 공유와 무관.)
 */
public final class SharedContainers {

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16");

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    private SharedContainers() {
    }
}
