package com.flowticket.fixture;

import org.springframework.beans.factory.annotation.Autowired;

public class Bad {
    @Autowired // 위반: 생성자 주입 사용
    Object dependency;
}
