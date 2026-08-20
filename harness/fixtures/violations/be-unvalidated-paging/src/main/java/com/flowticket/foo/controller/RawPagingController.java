package com.flowticket.foo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// VIOLATION: 페이징 파라미터를 검증 없이 받는다 → 하네스가 실패해야 함
// (?page=-1 이 PageRequest.of()까지 내려가 IllegalArgumentException → fallback 500)
@RestController
public class RawPagingController {

    @GetMapping("/foo")
    public String list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return page + ":" + size;
    }
}
