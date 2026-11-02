package com.flowticket.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.event.kopis.KopisSyncScheduler;
import com.flowticket.order.service.PaymentReconciliationService;
import com.flowticket.order.service.RefundReconciliationService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 외부 호출 작업이 공용 스케줄러 스레드를 붙잡지 않는지 검증한다.
 *
 * 통합테스트는 스케줄러를 끄고 돌아(flowticket.scheduling.enabled=false) 이 배선이 실행되지 않는다.
 * 그래서 설정만 띄워 실제로 어느 스레드에서 도는지 본다.
 */
class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingConfig.class, Probe.class);

    /** 외부 작업은 멈춰 있고 짧은 작업만 돈다. 스레드가 하나였다면 짧은 작업도 함께 멈춘다. */
    static class Probe {
        final CountDownLatch externalStarted = new CountDownLatch(1);
        final CountDownLatch shortTicks = new CountDownLatch(3);
        volatile String externalThread;
        volatile String shortThread;

        @Scheduled(fixedRate = 50, scheduler = "externalIoScheduler")
        void external() throws InterruptedException {
            externalThread = Thread.currentThread().getName();
            externalStarted.countDown();
            Thread.sleep(60_000);
        }

        @Scheduled(fixedRate = 50)
        void shortJob() {
            shortThread = Thread.currentThread().getName();
            shortTicks.countDown();
        }
    }

    @Test
    void 외부_호출_작업이_멈춰도_짧은_작업은_계속_돈다() {
        runner.run(context -> {
            Probe probe = context.getBean(Probe.class);

            assertThat(probe.externalStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.shortTicks.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(probe.externalThread).startsWith("external-io-");
            assertThat(probe.shortThread).startsWith("scheduling-");
        });
    }

    /** 짧은 작업들은 지금처럼 스레드 하나에서 순서대로 돈다. 늘리면 서로 동시에 도는 조합이 새로 생긴다. */
    @Test
    void 공용_스케줄러는_스레드_하나를_유지한다() {
        runner.run(context -> assertThat(context.getBean("taskScheduler",
                org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class)
                .getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1));
    }

    @Test
    void PG와_KOPIS를_부르는_작업은_전용_스케줄러를_쓴다() throws Exception {
        assertThat(schedulerOf(PaymentReconciliationService.class, "reconcileOrphanApprovals"))
                .isEqualTo("externalIoScheduler");
        assertThat(schedulerOf(RefundReconciliationService.class, "reconcileOrphanCancellations"))
                .isEqualTo("externalIoScheduler");
        assertThat(schedulerOf(KopisSyncScheduler.class, "scheduledSync"))
                .isEqualTo("externalIoScheduler");
    }

    private static String schedulerOf(Class<?> type, String method) throws Exception {
        return type.getMethod(method).getAnnotation(Scheduled.class).scheduler();
    }
}
