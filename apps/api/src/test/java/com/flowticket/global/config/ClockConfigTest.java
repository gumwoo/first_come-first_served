package com.flowticket.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 시계의 존을 못박는다.
 *
 * UTC 시계를 주입하면 LocalDateTime.now(clock)이 시스템 존과 어긋난다. 이 애플리케이션은 시각을
 * 시스템 존 기준으로 저장·직렬화하므로(TS-039), KST 환경에서는 9시간 과거가 돼 방금 만든 홀드가
 * 만료로 판정된다. 컴파일로는 드러나지 않고 한국 시간대 배포에서만 깨진다.
 */
class ClockConfigTest {

    @Test
    void 시계는_시스템_존을_쓴다() {
        ZoneId zone = new ClockConfig().clock().getZone();

        assertThat(zone).isEqualTo(ZoneId.systemDefault());
        if (!ZoneId.systemDefault().equals(ZoneOffset.UTC)) {
            assertThat(zone).isNotEqualTo(ZoneOffset.UTC);
        }
    }
}
