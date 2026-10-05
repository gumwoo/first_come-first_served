package com.flowticket.queue.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 대기열 상태 지표(loadtest-100k-plan §3.3·§5.1).
 *
 * 승격 워커가 매 틱 Redis에서 읽은 값을 그대로 내보낸다. 스크랩 때 Redis를 따로 조회하지 않는 이유는
 * 워커가 이미 같은 값을 읽고 있어서다 — 스크랩마다 이벤트 수만큼 Redis 왕복을 더할 이유가 없다.
 *
 * <b>파드마다 같은 값이 나온다.</b> 승격 워커는 ShedLock 없이 모든 파드에서 돈다(QueueAdmissionService 주석).
 * 값은 Redis의 전역 상태라 파드끼리 같고, 집계는 합이 아니라 {@code max by (event)}로 한다.
 *
 * over-admit은 {@code admitcount}가 아니라 {@code admitExp} 원소 수로 판정한다. 카운터는 승격 Lua가
 * 정원을 넘지 않게만 올리므로 구조상 정원을 넘지 않는다 — 막는 장치가 유지하는 값으로는 그 장치를 검사할 수 없다.
 * 둘을 함께 내보내 어긋남(누수·이중 차감, TS-024)도 본다.
 *
 * <b>어긋남은 따로 계산해 내보낸다(admit_drift).</b> 세 값을 한 Lua로 같은 시점에 읽어도 게이지마다 다른 AtomicLong에
 * 담기면, 스크랩이 admitted와 admit_count를 읽는 사이에 다음 틱이 끼어 서로 다른 틱의 값이 섞일 수 있다.
 * 그 둘을 빼서 판정하면 drain 구간처럼 값이 매 틱 바뀔 때 거짓 어긋남이 나온다. 그래서 같은 스냅숏에서 뺀
 * 값을 한 게이지로 내보내고, 판정은 그 게이지 하나만 본다.
 */
@Component
public class QueueMetrics {

    /** 한 이벤트의 한 틱 관측값. */
    record EventSnapshot(Long eventId, long waiting, long admitted, long admitCount) {}

    private final MultiGauge waiting;
    private final MultiGauge admitted;
    private final MultiGauge admitCount;
    private final MultiGauge admitDrift;
    private final Timer tick;
    private final Counter gateFallback;
    private final Counter tickFailures;

    /** 이벤트별 최신값. 게이지가 이 객체를 계속 읽으므로 틱마다 새로 등록하지 않는다. */
    private final Map<Long, Values> current = new ConcurrentHashMap<>();

    public QueueMetrics(MeterRegistry registry, @Value("${queue.capacity:100}") int capacity) {
        this.waiting = MultiGauge.builder("flowticket.queue.waiting")
                .description("대기 중인 토큰 수(wait ZSet 원소 수). 파드마다 같은 값 — max로 집계")
                .register(registry);
        this.admitted = MultiGauge.builder("flowticket.queue.admitted")
                .description("실제 입장 토큰 수(admitExp ZSet 원소 수). over-admit 판정 기준 — max로 집계")
                .register(registry);
        this.admitCount = MultiGauge.builder("flowticket.queue.admit_count")
                .description("입장 카운터 값(admitcount). admitted와 다르면 슬롯 누수 또는 이중 차감")
                .register(registry);
        this.admitDrift = MultiGauge.builder("flowticket.queue.admit_drift")
                .description("같은 스냅숏의 admit_count − admitted. 0이 아니면 카운터 어긋남(실시간 판정은 이 값만 본다)")
                .register(registry);
        Gauge.builder("flowticket.queue.capacity", () -> capacity)
                .description("이벤트당 동시 입장 정원(queue.capacity). over-admit 비교 기준")
                .register(registry);
        // 한 틱이 승격 주기(admit-interval-ms)를 넘으면 다음 틱이 밀린다 — 계획서의 "승격 지연".
        this.tick = Timer.builder("flowticket.queue.admit.tick")
                .description("승격 워커 한 틱(전 이벤트 회수·승격·관측) 소요 시간")
                .register(registry);
        // 회수된 토큰이 admit 키로 게이트에 온 횟수. 게이트가 거부하므로 정원 초과는 아니고, 그 창이 실제로 생겼다는 신호다
        // (회수 전 만료 토큰은 세지 않는다 — 아직 슬롯을 쥔 상태라 통과시킨다).
        this.gateFallback = Counter.builder("flowticket.queue.gate.fallback")
                .description("이미 회수돼 admitExp에 없는 토큰이 admit 키로 입장 게이트에 왔다가 거부된 횟수(실효 입장 초과 창). 파드 합으로 집계")
                .register(registry);
        // 처리에 실패한 활성 이벤트는 직전 값을 유지한다. 실패가 이어지면 게이지가 마지막 값에 고정되므로,
        // 실패 횟수를 따로 내보내 "값이 멈춘 것"과 "상태가 그대로인 것"을 구분한다.
        this.tickFailures = Counter.builder("flowticket.queue.admit.tick.failures")
                .description("승격 워커가 이벤트 하나를 처리하다 실패한 횟수. 0보다 크면 대기열 게이지가 멈췄을 수 있다")
                .register(registry);
    }

    Counter tickFailures() {
        return tickFailures;
    }

    Counter gateFallback() {
        return gateFallback;
    }

    Timer tickTimer() {
        return tick;
    }

    /**
     * 이번 틱의 관측값을 반영한다.
     *
     * @param observed 이번 틱에 값을 읽은 이벤트
     * @param retained 값을 읽지 못했어도 아직 활성인 이벤트(처리 중 예외). 직전 값을 유지한다 —
     *                 한 틱 실패로 시계열이 끊기면 그 구간이 "대기 0"과 구분되지 않는다.
     */
    void publish(List<EventSnapshot> observed, Set<Long> retained) {
        for (EventSnapshot s : observed) {
            Values v = current.computeIfAbsent(s.eventId(), id -> new Values());
            v.waiting.set(s.waiting());
            v.admitted.set(s.admitted());
            v.admitCount.set(s.admitCount());
            v.admitDrift.set(s.admitCount() - s.admitted());
        }
        current.keySet().removeIf(id -> observed.stream().noneMatch(s -> s.eventId().equals(id))
                && !retained.contains(id));

        // 사라진 이벤트의 행은 MultiGauge가 지운다. 남은 행은 같은 AtomicLong을 계속 읽는다(overwrite=false).
        waiting.register(rows(v -> v.waiting), false);
        admitted.register(rows(v -> v.admitted), false);
        admitCount.register(rows(v -> v.admitCount), false);
        admitDrift.register(rows(v -> v.admitDrift), false);
    }

    private List<MultiGauge.Row<?>> rows(Function<Values, AtomicLong> field) {
        List<MultiGauge.Row<?>> rows = new ArrayList<>(current.size());
        current.forEach((eventId, v) -> rows.add(MultiGauge.Row.of(Tags.of("event", String.valueOf(eventId)), field.apply(v))));
        return rows;
    }

    private static final class Values {
        final AtomicLong waiting = new AtomicLong();
        final AtomicLong admitted = new AtomicLong();
        final AtomicLong admitCount = new AtomicLong();
        final AtomicLong admitDrift = new AtomicLong();
    }
}
