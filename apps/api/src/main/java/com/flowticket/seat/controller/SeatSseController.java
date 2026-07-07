package com.flowticket.seat.controller;

import com.flowticket.seat.sse.SeatSseRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 좌석맵 실시간(이벤트별). 선점/해제/만료 시 seat.held·seat.hold.released·seat.hold.expired 를
 *  구독자에 push. /sse는 permitAll. */
@RestController
public class SeatSseController {

    private final SeatSseRegistry registry;

    public SeatSseController(SeatSseRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/sse/events/{id}/seats", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id) {
        return registry.subscribe(id);
    }
}
