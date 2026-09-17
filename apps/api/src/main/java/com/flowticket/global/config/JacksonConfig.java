package com.flowticket.global.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code LocalDateTime}을 서버 존 오프셋을 붙여 내보낸다.
 *
 * <p>오프셋이 없으면 브라우저가 UTC 서버 시각을 로컬(KST)로 읽어 만료 시각이 9시간 어긋난다.
 * 로컬에서는 재현되지 않는다(TS-039).
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeWithServerOffset() {
        JsonSerializer<LocalDateTime> serializer = new JsonSerializer<>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                // LocalDateTime.now()가 시스템 존 기준이므로, 되돌릴 때도 같은 존을 써야 한다.
                gen.writeString(value.atZone(ZoneId.systemDefault())
                        .toOffsetDateTime()
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            }
        };
        return builder -> builder.serializerByType(LocalDateTime.class, serializer);
    }
}
