package com.flowticket.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 배경 스케줄러 활성화. 통합테스트에서 끌 수 있게 애플리케이션 클래스에서 분리했다.
 *
 * 주기를 크게 잡는 것으로는 못 막는다. @Scheduled(fixedRateString = ...)는 컨텍스트가 뜨자마자
 * 첫 실행이 발사되고, 그 스윕의 UPDATE가 테스트 초기화의 TRUNCATE와 데드락을 만든다.
 * 스윕이 필요한 테스트는 서비스 메서드를 직접 호출한다.
 *
 * 스케줄러를 둘로 나눈다. 대기열 승격(1.5초)·아웃박스 릴레이(1초)는 공용 스레드 하나에서 돈다. 여기에 외부 API를
 * 부르는 작업이 얹히면 응답이 늦는 동안 승격·릴레이가 함께 멈춘다. 정산은 건당 읽기 시간 초과가 10초라 한 틱이
 * 수 분을 넘을 수 있다. 공용 풀을 늘리지 않은 것은 승격·릴레이·sweep이 한 Pod 안에서 서로 동시에 도는 조합이
 * 새로 생기기 때문이다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "flowticket.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    /** 외부 API를 부르는 작업 전용. @Scheduled(scheduler = EXTERNAL_IO)로 지정한다. */
    public static final String EXTERNAL_IO = "externalIoScheduler";

    /** 전용 스케줄러를 쓰는 작업 수와 같다. 한 작업이 오래 걸려도 다른 작업을 기다리게 하지 않는다. */
    private static final int EXTERNAL_IO_THREADS = 3;

    /**
     * 공용 스케줄러. 이름이 taskScheduler여야 한다. TaskScheduler 빈이 둘이면 Spring이 이 이름으로 기본값을 고른다.
     * 빈을 하나라도 직접 만들면 Boot 기본 스케줄러가 뜨지 않으므로 기본과 같은 설정(스레드 하나)으로 함께 만든다.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean(EXTERNAL_IO)
    public ThreadPoolTaskScheduler externalIoScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(EXTERNAL_IO_THREADS).threadNamePrefix("external-io-").build();
    }

    /**
     * Boot 기본 실행기를 그대로 되살린다. 스케줄러도 Executor라 위 빈들 때문에 Boot의 것이 물러나는데, 그대로 두면
     * 스케줄러가 켜진 운영에서만 이 빈이 없어 비동기 MVC 등이 상한 없는 기본 실행기로 조용히 떨어진다.
     */
    @Lazy
    @Bean(name = {TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME,
            AsyncAnnotationBeanPostProcessor.DEFAULT_TASK_EXECUTOR_BEAN_NAME})
    public ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder.build();
    }
}
