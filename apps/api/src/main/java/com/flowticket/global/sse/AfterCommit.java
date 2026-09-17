package com.flowticket.global.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션이 커밋된 뒤에만 실행되는 부수효과.
 *
 * 트랜잭션 안에서 SSE를 보내면 롤백됐을 때 일어나지 않은 일을 알리게 된다("좌석이 잡혔다" 알림 →
 * 재조회하면 AVAILABLE). 결제 확정은 만료 sweep에 져 롤백될 수 있다(TS-011).
 *
 * 반드시 도달해야 하는 이벤트(order.paid)는 아웃박스로 보낸다(ADR-010). 좌석맵·주문상태 갱신 알림은
 * 놓쳐도 폴링·재조회가 메우므로(ADR-008, TS-012) 좌석 선점 경로에 DB 쓰기를 더하지 않는다.
 *
 * 트랜잭션이 없으면(스케줄러 밖 호출·Kafka 소비 등) 바로 실행한다.
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 여기서 던지면 커밋은 이미 끝났는데 요청이 500으로 나간다.
                // 알림 실패가 성공한 거래를 실패로 보이게 해선 안 된다(SSE는 best-effort).
                try {
                    action.run();
                } catch (RuntimeException e) {
                    log.warn("[sse] 커밋 후 발송 실패(무시): {}", e.getMessage());
                }
            }
        });
    }
}
