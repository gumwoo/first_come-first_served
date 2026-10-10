package com.flowticket.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowticket.queue.domain.QueueStatus;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 다음 상태 조회 최소 대기(retryAfterMs): 앞쪽(순번 ≤ 정원 × 2)은 최소값, 그 뒤는 ceil(순번 ÷ 정원) × 승격 주기를
 * [최소, 상한]으로 자른 값, 종료 상태는 0. 정원 100·승격 1.5초·2~30초(운영 기본값).
 */
class QueueRetryAfterTest {

    private final QueueService service = new QueueService(mock(StringRedisTemplate.class), mock(BookableEventCache.class),
            100, 1800, 21600, 1500, 2000, 30000, Clock.systemUTC(), mock(QueueMetrics.class));

    @Test
    void 앞쪽_순번은_최소_대기() {
        assertThat(service.retryAfterMs(QueueStatus.WAITING, 1)).isEqualTo(2000);
        assertThat(service.retryAfterMs(QueueStatus.WAITING, 200)).isEqualTo(2000); // 경계: 정원 × 2
    }

    @Test
    void 뒤쪽은_정원씩_빠질_때_입장까지_시간이고_상한에서_멈춘다() {
        assertThat(service.retryAfterMs(QueueStatus.WAITING, 201)).isEqualTo(4500);   // ceil(2.01) = 3 × 1.5초
        assertThat(service.retryAfterMs(QueueStatus.WAITING, 1000)).isEqualTo(15000); // 10 × 1.5초
        assertThat(service.retryAfterMs(QueueStatus.WAITING, 2000)).isEqualTo(30000); // 20 × 1.5초 = 상한
        assertThat(service.retryAfterMs(QueueStatus.WAITING, 100000)).isEqualTo(30000);
    }

    @Test
    void 종료_상태는_0() {
        assertThat(service.retryAfterMs(QueueStatus.ADMITTED, 0)).isZero();
        assertThat(service.retryAfterMs(QueueStatus.EXPIRED, 0)).isZero();
    }
}
