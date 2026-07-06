package com.flowticket.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

@Entity
public class Bad {
    @Id
    Long id;

    @Enumerated(EnumType.ORDINAL) // 위반: STRING이어야 함
    Status status;

    enum Status { A, B }
}
