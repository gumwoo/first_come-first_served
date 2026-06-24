package com.flowticket.foo.controller;

import org.springframework.web.bind.annotation.RestController;

// VIOLATION: 컨트롤러 내 try/catch로 예외 삼키기 → 하네스가 실패해야 함
@RestController
public class SwallowController {
    public String run() {
        try {
            return "ok";
        } catch (RuntimeException e) {
            return "swallowed";
        }
    }
}
