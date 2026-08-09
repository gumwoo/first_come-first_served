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
 * {@code LocalDateTime}을 <b>오프셋을 붙여</b> 내보낸다.
 *
 * <p>기본 직렬화는 {@code "2026-08-09T06:56:54"}처럼 타임존이 없다. 그런데 JS의
 * {@code new Date("2026-08-09T06:56:54")}는 오프셋이 없으면 그 값을 <b>브라우저 로컬 시간</b>으로
 * 해석한다(ES 명세). 서버 컨테이너는 UTC이고 사용자는 KST라 <b>9시간이 어긋난다</b>.
 *
 * <p>실제로 좌석 선점이 이것 때문에 깨졌다: 5분짜리 선점의 만료 시각을 브라우저가 9시간 전으로
 * 계산해, '선택 완료'를 누르는 즉시 만료 화면으로 튕겼다. 남은 시간 표시는 5분에서 멀쩡히
 * 내려가는 중이었으므로 <b>에러가 아니라 다른 화면</b>으로 빠지는 유형이다.
 *
 * <p>로컬 개발에서는 절대 재현되지 않는다 — JVM도 브라우저도 같은 KST라 차이가 0이다.
 * 시차가 있는 배포 환경에서만 드러난다.
 *
 * <p>더 원칙적인 수정은 "순간"을 뜻하는 필드의 타입을 {@code Instant}로 바꾸는 것이다. 다만 그건
 * DTO와 매퍼 여러 곳을 동시에 건드려야 해서, 우선 <b>나가는 표현</b>을 고쳐 전 엔드포인트를 한 번에
 * 정상화한다. 요청 바디에는 {@code LocalDateTime} 필드가 없어 역직렬화에는 영향이 없다.
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
