package com.flowticket.demo;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 위반 fixture: 트랜잭션 프록시를 타려고 자기 자신을 주입받는다. */
@Service
public class DemoService {

    private final ObjectProvider<DemoService> self;

    public DemoService(ObjectProvider<DemoService> self) {
        this.self = self;
    }

    public void run() {
        self.getObject().runTx();
    }

    @Transactional
    public void runTx() {
    }
}
