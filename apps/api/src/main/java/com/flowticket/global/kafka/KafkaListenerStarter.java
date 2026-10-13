package com.flowticket.global.kafka;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Kafka 리스너를 앱 기동과 분리해 켠다(TS-016).
 *
 * 리스너가 스프링 라이프사이클로 뜨면, 브로커 주소가 DNS로 풀리지 않을 때 컨슈머 생성 실패가 컨텍스트 refresh를
 * 취소시켜 API가 기동하지 못한다 — 알림(부가 경로) 장애가 API 장애로 번진다. 그래서 컨테이너는 autoStartup=false로 두고
 * 기동 직후 여기서 켜며, 실패하면 앱은 그대로 두고 주기적으로 다시 켠다.
 * 꺼져 있는 동안 발행은 아웃박스가 PENDING으로 보존하고(ADR-010), 켜지면 그룹 오프셋부터 이어 소비한다.
 */
@Component
public class KafkaListenerStarter {

    private static final Logger log = LoggerFactory.getLogger(KafkaListenerStarter.class);

    private final KafkaListenerEndpointRegistry registry;
    private final Duration retryInterval;
    private final ScheduledExecutorService retryExecutor;

    private boolean closing;
    private int attempts;

    public KafkaListenerStarter(KafkaListenerEndpointRegistry registry,
                                @Value("${kafka.listener.start-retry-interval:PT10S}") Duration retryInterval) {
        this.registry = registry;
        this.retryInterval = retryInterval;
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kafka-listener-starter");
            t.setDaemon(true);
            return t;
        });
    }

    /** readiness보다 앞(러너·ApplicationReady 이전)에서 켠다 — 브로커가 있으면 예전처럼 트래픽 전에 소비가 시작된다. */
    @EventListener(ApplicationStartedEvent.class)
    public void onStarted() {
        startPending();
    }

    /** 라이프사이클 정지(레지스트리가 컨테이너를 멈춤)보다 먼저 온다. 진행 중인 시도가 끝난 뒤 재시도를 멈춘다. */
    @EventListener(ContextClosedEvent.class)
    public synchronized void onClosed() {
        closing = true;
        retryExecutor.shutdownNow();
    }

    /** 아직 실행 중이 아닌 컨테이너를 켜고, 켜지 못한 수를 돌려준다. 하나라도 실패하면 재시도를 예약한다. */
    synchronized int startPending() {
        if (closing) {
            return 0;
        }
        attempts++;
        int failed = 0;
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (container.isRunning()) {
                continue;
            }
            try {
                container.start();
            } catch (RuntimeException e) {
                failed++;
                // ConcurrentMessageListenerContainer는 자식 컨슈머를 만들기 전에 running=true로 둔다.
                // 그대로 두면 다음 start()가 아무것도 하지 않으므로 상태를 되돌린다.
                stopQuietly(container);
                log.warn("[kafka] 리스너 {} 기동 실패(시도 {}), {}초 뒤 다시 시도: {}",
                        container.getListenerId(), attempts, retryInterval.toSeconds(), e.toString());
            }
        }
        if (failed > 0) {
            scheduleRetry();
        } else if (attempts > 1) {
            log.info("[kafka] 리스너 전부 기동(시도 {})", attempts);
        }
        return failed;
    }

    private void scheduleRetry() {
        try {
            // void 메서드로 넘긴다 — startPending(int 반환)을 그대로 넘기면 schedule(Callable)로 해석된다.
            retryExecutor.schedule(this::retry, retryInterval.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // 종료 중이면 실행기가 이미 닫혔다. 다시 켤 이유가 없다.
        }
    }

    private void retry() {
        startPending();
    }

    private static void stopQuietly(MessageListenerContainer container) {
        try {
            container.stop();
        } catch (RuntimeException e) {
            log.debug("[kafka] 실패한 리스너 정리 중 예외(무시): {}", e.toString());
        }
    }
}
