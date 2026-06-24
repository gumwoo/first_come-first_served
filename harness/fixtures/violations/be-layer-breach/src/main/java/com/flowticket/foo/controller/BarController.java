package com.flowticket.foo.controller;

import com.flowticket.foo.repository.BarRepository; // VIOLATION: controller→repository 직접 import
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BarController {
    private final BarRepository repo;
    BarController(BarRepository repo) { this.repo = repo; }
}
