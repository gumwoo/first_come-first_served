package com.flowticket.fixture;

import org.springframework.transaction.annotation.Transactional;

public class Bad {
    @Transactional // 위반: private 메서드엔 프록시 미적용
    private void doIt() {}
}
