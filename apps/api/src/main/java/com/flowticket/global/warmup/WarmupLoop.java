package com.flowticket.global.warmup;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 정해진 시간 동안 여러 스레드로 같은 호출을 반복한다. 실패·예외는 세기만 하고 던지지 않는다 —
 * 워밍업이 실패했다고 파드가 뜨지 못하면 안 된다(워밍업은 성능 장치지 기동 조건이 아니다).
 */
final class WarmupLoop {

    record Result(long requests, long failures, long elapsedMs) {
    }

    private final BooleanSupplier call;
    private final long durationNanos;
    private final int threads;
    private final LongSupplier clock;

    WarmupLoop(BooleanSupplier call, long durationMs, int threads, LongSupplier clock) {
        this.call = call;
        this.durationNanos = TimeUnit.MILLISECONDS.toNanos(durationMs);
        this.threads = Math.max(1, threads);
        this.clock = clock;
    }

    Result run() {
        long start = clock.getAsLong();
        long deadline = start + durationNanos;
        AtomicLong requests = new AtomicLong();
        AtomicLong failures = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "warmup");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                while (clock.getAsLong() < deadline && !Thread.currentThread().isInterrupted()) {
                    requests.incrementAndGet();
                    try {
                        if (!call.getAsBoolean()) {
                            failures.incrementAndGet();
                        }
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                    }
                }
            });
        }
        pool.shutdown();
        try {
            // 호출 하나가 읽기 타임아웃까지 걸려도 끝나도록 여유를 둔다. 못 끝나면 끊고 진행한다.
            if (!pool.awaitTermination(TimeUnit.NANOSECONDS.toMillis(durationNanos) + 10_000, TimeUnit.MILLISECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        return new Result(requests.get(), failures.get(), TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - start));
    }
}
