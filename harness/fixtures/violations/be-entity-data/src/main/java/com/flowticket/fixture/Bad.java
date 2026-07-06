package com.flowticket.fixture;

import jakarta.persistence.Entity;
import lombok.Data;

@Data   // 위반: 엔티티에 @Data 금지
@Entity
public class Bad {
    Long id;
}
