package com.flowticket.global.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 스케줄러 락(멀티 Pod). @Scheduled는 Pod마다 실행돼 중복 sweep/승격이 돈다. 정합성은 조건부
 * UPDATE로 이미 안전하지만(중복은 0행 처리) 낭비이므로, ShedLock으로 같은 작업이 동시에 돌지 않게 한다.
 * "주기당 한 번"은 보장하지 않는다 — lockAtLeastFor=PT0S면 락이 실행 직후 풀려, 같은 주기에 다른 Pod의 틱이
 * 다시 실행할 수 있다. 그래서 각 작업은 반복 실행돼도 결과가 같아야 한다(TS-051).
 * Redis lock provider(이미 쓰는 Redis 재사용, 새 컴포넌트 0). 락은 트랜잭션 바깥에서 획득된다.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "flowticket");
    }
}
