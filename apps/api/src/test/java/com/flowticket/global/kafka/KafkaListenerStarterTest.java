package com.flowticket.global.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;

/** TS-016: 리스너 기동 실패가 앱을 죽이지 않고, 실패한 컨테이너만 상태를 되돌려 다시 시도한다. */
@ExtendWith(MockitoExtension.class)
class KafkaListenerStarterTest {

    private static final Duration FAST = Duration.ofMillis(50);
    private static final Duration NEVER_IN_TEST = Duration.ofHours(1);

    @Mock KafkaListenerEndpointRegistry registry;
    @Mock MessageListenerContainer failing;
    @Mock MessageListenerContainer running;

    KafkaListenerStarter starter;

    @AfterEach
    void tearDown() {
        if (starter != null) {
            starter.onClosed(); // 재시도 스레드 정리
        }
    }

    @Test
    void 기동_실패는_예외로_번지지_않고_상태를_되돌린_뒤_다시_시도한다() {
        starter = new KafkaListenerStarter(registry, FAST);
        when(registry.getListenerContainers()).thenReturn(List.of(failing, running));
        when(failing.isRunning()).thenReturn(false);
        when(running.isRunning()).thenReturn(true);
        doThrow(new KafkaException("Failed to construct kafka consumer"))
                .doNothing()
                .when(failing).start();

        assertThat(starter.startPending()).isEqualTo(1);

        // ConcurrentMessageListenerContainer가 running=true로 남는 것을 되돌려야 다음 start()가 실제로 돈다.
        verify(failing).stop();
        verify(running, never()).start();
        // 재시도가 실제로 돌아 두 번째에 켜지고, 그 뒤로는 더 시도하지 않는다.
        verify(failing, timeout(2_000).times(2)).start();
        verify(failing, after(300).times(2)).start();
    }

    @Test
    void 종료가_시작되면_더_켜지_않는다() {
        starter = new KafkaListenerStarter(registry, FAST);
        starter.onClosed();

        assertThat(starter.startPending()).isZero();

        verify(registry, never()).getListenerContainers();
    }

    @Test
    void 브로커가_있으면_한_번에_모두_켜고_재시도하지_않는다() {
        starter = new KafkaListenerStarter(registry, FAST);
        when(registry.getListenerContainers()).thenReturn(List.of(failing));
        when(failing.isRunning()).thenReturn(false);
        doNothing().when(failing).start();

        assertThat(starter.startPending()).isZero();

        verify(failing, never()).stop();
        verify(failing, after(300).times(1)).start();
    }

    /**
     * 목이 아니라 실제 컨테이너로 전제를 확인한다: 브로커 주소가 해석되지 않으면 start()가 예외를 던지고,
     * ConcurrentMessageListenerContainer는 running=true로 남는다 — 되돌리지 않으면 이후 start()가 조용히 아무것도 하지 않는다.
     */
    @Test
    void 실제_컨테이너는_실패_뒤_되돌려져_다음_시도가_다시_컨슈머를_만든다() {
        starter = new KafkaListenerStarter(registry, NEVER_IN_TEST);
        ConcurrentMessageListenerContainer<String, String> container = unresolvableContainer();
        when(registry.getListenerContainers()).thenReturn(List.of(container));

        assertThat(starter.startPending()).isEqualTo(1);
        assertThat(container.isRunning()).isFalse(); // 되돌렸다

        assertThat(starter.startPending()).isEqualTo(1); // 조용히 넘어가지 않고 다시 실패한다(= 다시 시도했다)
    }

    @Test
    void 되돌리지_않으면_실제_컨테이너는_다음_start를_무시한다() {
        ConcurrentMessageListenerContainer<String, String> container = unresolvableContainer();
        try {
            container.start();
        } catch (KafkaException expected) {
            // 첫 시도는 예외
        }
        assertThat(container.isRunning()).isTrue(); // 실패했는데 실행 중으로 남는다
        container.start(); // 예외도 없이 아무것도 하지 않는다 — 이 상태가 재시도를 무력화한다
        assertThat(container.isRunning()).isTrue();
        container.stop();
    }

    private static ConcurrentMessageListenerContainer<String, String> unresolvableContainer() {
        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "flowticket-kafka-bootstrap.does-not-exist.invalid:9092",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        ContainerProperties props = new ContainerProperties("order-events");
        props.setGroupId("ts016-test");
        props.setMessageListener((MessageListener<String, String>) record -> { });
        return new ConcurrentMessageListenerContainer<>(factory, props);
    }
}
