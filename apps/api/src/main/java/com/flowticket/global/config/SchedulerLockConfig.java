package com.flowticket.global.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 스케줄러 락(멀티 Pod). @Scheduled는 Pod마다 실행돼 중복 sweep/승격이 돈다 — 정합성은 조건부
 * UPDATE로 이미 안전하지만(중복은 0행 처리) 낭비이므로, ShedLock으로 각 틱을 한 Pod만 실행하게 한다.
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
