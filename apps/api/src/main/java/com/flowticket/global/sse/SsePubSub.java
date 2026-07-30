package com.flowticket.global.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * SSE 팬아웃(멀티 Pod). 인메모리 SSE 레지스트리는 Pod 로컬이라, 이벤트를 만든 Pod와 SSE 연결을
 * 들고 있는 Pod가 다르면 알림이 누락된다. 그래서 브로드캐스트를 Redis pub/sub 채널로 발행하고,
 * 모든 Pod가 구독해 각자 로컬 SSE로 전달한다(자기 자신 포함, 단일 전달 경로).
 *
 * <p>Kafka(내구성 이벤트 백본)를 대체하는 게 아니라 "Pod 간 마지막 홉 팬아웃" 단계다 —
 * best-effort(미저장) 성격이 "SSE는 보조·DB가 진실원(ADR-008)"과 일치.
 */
@Slf4j
@Component
public class SsePubSub {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public SsePubSub(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /** 팬아웃 봉투: key=대상 식별자(eventId/orderId/token 문자열), event=SSE 이벤트명, data=페이로드. */
    public record Envelope(String key, String event, Object data) {}

    /** 이벤트를 채널로 발행 → 구독 중인 모든 Pod가 로컬 전달. 발행 실패는 격리(SSE는 best-effort). */
    public void publish(String channel, String key, String event, Object data) {
        try {
            redis.convertAndSend(channel, mapper.writeValueAsString(new Envelope(key, event, data)));
        } catch (Exception e) {
            log.warn("[sse] 팬아웃 발행 실패 channel={} key={}: {}", channel, key, e.getMessage());
        }
    }

    /** 수신 메시지 본문(JSON)을 봉투로 파싱. 실패 시 null(격리). */
    public Envelope parse(String body) {
        try {
            return mapper.readValue(body, Envelope.class);
        } catch (Exception e) {
            log.warn("[sse] 팬아웃 수신 파싱 실패: {}", e.getMessage());
            return null;
        }
    }
}
