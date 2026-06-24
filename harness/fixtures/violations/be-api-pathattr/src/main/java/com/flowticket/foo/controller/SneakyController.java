package com.flowticket.foo.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// VIOLATION: path= 속성 형태로 계약에 없는 endpoint 추가.
// 구버전 정규식은 놓쳤으나 강화된 검사는 잡아야 함. (POST /sneaky/hidden)
@RestController
@RequestMapping(path = "/sneaky")
public class SneakyController {

    @PostMapping(path = "/hidden")
    public String hidden() {
        return "gotcha";
    }
}
