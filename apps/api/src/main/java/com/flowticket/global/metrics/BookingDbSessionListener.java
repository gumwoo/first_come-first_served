package com.flowticket.global.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.hibernate.SessionEventListener;

/**
 * 예매 요청 한 세션(= 트랜잭션 하나)이 DB를 어떻게 썼는지 잰다. B₁ 예매 지연의 원인을 가르기 위한 계측이다(IMP-034 §6).
 *
 * IMP-034에서 예매 p95가 B₀보다 컸는데 CPU·커넥션 대기·GC는 놀았고, api 파드와 RDS 주 AZ 사이 왕복이 3~6배였다(실측).
 * "트랜잭션 길이 = DB 왕복 수 × 왕복 시간이라 AZ 배치가 지연을 좌우한다"는 추론을 검증하려면 요청당 왕복 수와
 * 커넥션을 쥔 시간을 따로 봐야 한다. 그래서 세션마다 네 값을 남긴다(태그 op — BookingDbOp).
 *   - acquire: 커넥션 풀에서 커넥션을 얻기까지(풀 경합)
 *   - held: 커넥션을 쥔 시간(≈ 트랜잭션 길이, 커밋 포함)
 *   - execute: JDBC 실행 시간 합(왕복 + DB 처리 + 행 잠금 대기 — 잠금 대기를 따로 떼지 못한다)
 *   - statements: 실행한 SQL 수(배치는 1회). execute ÷ statements를 AZ별 왕복과 비교하면 왕복 몫을 계산할 수 있다
 * held − execute는 트랜잭션 안에서 DB가 아닌 데 쓴 시간(앱 처리·커밋 왕복)이다.
 *
 * Hibernate가 세션마다 이 클래스를 리플렉션으로 만든다(hibernate.session.events.auto) — 스프링 빈이 아니다.
 * 그래서 동작 이름은 요청 스레드(BookingDbContext)에서 읽고, 지표는 전역 레지스트리(Metrics.globalRegistry)에 남긴다.
 * 스프링 부트가 자기 레지스트리를 전역 레지스트리에 붙이므로(management.metrics.use-global-registry 기본 true)
 * 프로메테우스로 나간다. 예매 동작이 아닌 세션(조회·스케줄러)은 시각도 재지 않는다.
 */
public class BookingDbSessionListener implements SessionEventListener {

    static final String ACQUIRE = "flowticket.booking.db.acquire";
    static final String HELD = "flowticket.booking.db.held";
    static final String EXECUTE = "flowticket.booking.db.execute";
    static final String STATEMENTS = "flowticket.booking.db.statements";

    private final transient LongSupplier clock;
    private final String op;

    private long acquireStart;
    private long acquireNanos;
    private boolean acquired;
    private long heldStart;
    private boolean holding;
    private long heldNanos;
    private long executeStart;
    private long executeNanos;
    private int statements;

    public BookingDbSessionListener() {
        this(System::nanoTime, BookingDbContext.current());
    }

    BookingDbSessionListener(LongSupplier clock, String op) {
        this.clock = clock;
        this.op = op;
    }

    @Override
    public void jdbcConnectionAcquisitionStart() {
        if (op == null) {
            return;
        }
        acquireStart = clock.getAsLong();
    }

    @Override
    public void jdbcConnectionAcquisitionEnd() {
        if (op == null) {
            return;
        }
        long now = clock.getAsLong();
        acquireNanos += now - acquireStart;
        heldStart = now;
        holding = true;
        acquired = true;
    }

    @Override
    public void jdbcConnectionReleaseStart() {
        if (op == null || !holding) {
            return;
        }
        heldNanos += clock.getAsLong() - heldStart;
        holding = false;
    }

    @Override
    public void jdbcExecuteStatementStart() {
        startExecute();
    }

    @Override
    public void jdbcExecuteStatementEnd() {
        endExecute();
    }

    @Override
    public void jdbcExecuteBatchStart() {
        startExecute();
    }

    @Override
    public void jdbcExecuteBatchEnd() {
        endExecute();
    }

    @Override
    public void end() {
        if (op == null || !acquired) {
            return; // 커넥션을 한 번도 얻지 않은 세션(1차 캐시만 본 경우)은 남기지 않는다
        }
        if (holding) {
            jdbcConnectionReleaseStart();
        }
        Timer.builder(ACQUIRE).tag("op", op)
                .description("예매 세션이 커넥션 풀에서 커넥션을 얻기까지 걸린 시간")
                .register(Metrics.globalRegistry).record(acquireNanos, TimeUnit.NANOSECONDS);
        Timer.builder(HELD).tag("op", op)
                .description("예매 세션이 커넥션을 쥔 시간(트랜잭션 길이, 커밋 포함)")
                .register(Metrics.globalRegistry).record(heldNanos, TimeUnit.NANOSECONDS);
        Timer.builder(EXECUTE).tag("op", op)
                .description("예매 세션의 JDBC 실행 시간 합(왕복·DB 처리·행 잠금 대기)")
                .register(Metrics.globalRegistry).record(executeNanos, TimeUnit.NANOSECONDS);
        DistributionSummary.builder(STATEMENTS).tag("op", op)
                .description("예매 세션이 실행한 SQL 수(배치는 1회)")
                .register(Metrics.globalRegistry).record(statements);
    }

    private void startExecute() {
        if (op == null) {
            return;
        }
        executeStart = clock.getAsLong();
    }

    private void endExecute() {
        if (op == null) {
            return;
        }
        executeNanos += clock.getAsLong() - executeStart;
        statements++;
    }
}
