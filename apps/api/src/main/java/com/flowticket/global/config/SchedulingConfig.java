package com.flowticket.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 배경 스케줄러 활성화. 통합테스트에서 끌 수 있게 애플리케이션 클래스에서 분리했다.
 *
 * 주기를 크게 잡는 것으로는 못 막는다. @Scheduled(fixedRateString = ...)는 컨텍스트가 뜨자마자
 * 첫 실행이 발사되고, 그 스윕의 UPDATE가 테스트 초기화의 TRUNCATE와 데드락을 만든다.
 * 스윕이 필요한 테스트는 서비스 메서드를 직접 호출한다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "flowticket.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
