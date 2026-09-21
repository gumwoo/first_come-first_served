package com.flowticket.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간의 출처를 하나로 모은다.
 *
 * 이 도메인에서 시간은 부수적인 값이 아니라 판정 기준이다. 홀드 만료, 주문 만료, 환불 가능 시점,
 * 대기열 입장 TTL, 정산 유예가 모두 "지금이 언제인가"로 결과가 갈린다. 코드가 LocalDateTime.now()를
 * 직접 부르면 그 판정을 테스트에서 고정할 수 없어, 경계를 확인하려면 sleep을 넣거나 DB 시각을
 * 손으로 밀어야 한다.
 *
 * systemUTC()가 아니라 systemDefaultZone()이다. 이 애플리케이션의 시각은 시스템 존 기준으로
 * 저장·직렬화된다(JacksonConfig, TS-039). UTC 클럭을 주입하면 LocalDateTime.now(clock)이 9시간
 * 과거가 돼 방금 만든 홀드가 만료로 판정된다. 컨테이너의 TZ는 k8s에서 고정한다(하네스 k8s 규칙 7).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
