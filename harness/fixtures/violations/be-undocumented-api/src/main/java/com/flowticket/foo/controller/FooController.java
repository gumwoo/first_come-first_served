package com.flowticket.foo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// VIOLATION: contracts/api.yaml에 없는 endpoint → 하네스가 실패해야 함
@RestController
public class FooController {

    @GetMapping("/totally-not-in-contract")
    public String ghost() {
        return "boo";
    }
}
