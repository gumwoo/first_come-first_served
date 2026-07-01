package com.flowticket.queue.controller;

import com.flowticket.queue.sse.QueueSseRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 대기열 실시간 push. 토큰이 접근 비밀값(브라우저 EventSource는 헤더를 못 붙임) → /sse는 permitAll.
 * 승격/만료 시 QueueSseRegistry가 queue.admitted / queue.expired 를 전송.
 */
@RestController
public class QueueSseController {

    private final QueueSseRegistry registry;

    public QueueSseController(QueueSseRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/sse/queue/{token}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String token) {
        return registry.subscribe(token);
    }
}
