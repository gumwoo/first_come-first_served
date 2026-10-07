package com.flowticket.global.config;

import com.flowticket.order.sse.OrderSseRegistry;
import com.flowticket.seat.sse.SeatSseRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * SSE 팬아웃 구독(멀티 Pod). 각 SSE 레지스트리를 자기 채널의 Redis pub/sub 리스너로 등록한다.
 * 어느 Pod가 발행하든 모든 Pod가 수신해 로컬 SSE로 전달(연결 Pod ≠ 소비 Pod 알림 누락 해소).
 * 대기열은 SSE를 쓰지 않는다 — 대기 상태는 폴링으로 전달한다(ADR-023 §2).
 */
@Configuration
public class SseRedisConfig {

    @Bean
    public RedisMessageListenerContainer sseListenerContainer(
            RedisConnectionFactory connectionFactory,
            SeatSseRegistry seatSse,
            OrderSseRegistry orderSse) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(seatSse, new ChannelTopic(SeatSseRegistry.CHANNEL));
        container.addMessageListener(orderSse, new ChannelTopic(OrderSseRegistry.CHANNEL));
        return container;
    }
}
