package com.flowticket.foo.service;

// VIOLATION: 시크릿 하드코딩 → 하네스가 실패해야 함
public class LeakService {
    private static final String secret = "super-secret-jwt-key-123";

    public void run() {
        try {
            doWork();
        } catch (Exception e) {
            e.printStackTrace(); // VIOLATION: printStackTrace
        }
    }

    private void doWork() {}
}
