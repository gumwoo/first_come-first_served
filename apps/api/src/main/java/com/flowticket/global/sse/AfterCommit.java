package com.flowticket.global.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션이 <b>커밋된 뒤에만</b> 실행되는 부수효과.
 *
 * <p>SSE 알림은 트랜잭션 안에서 보내면 <b>아직 커밋되지 않은 상태를 알린다.</b> 뒤에서 예외가
 * 나 롤백되면 클라이언트는 일어나지 않은 일을 통보받고, 그 알림을 받아 재조회한 화면은 옛 상태를
 * 보게 된다("좌석이 잡혔다"는 알림 → 조회하면 AVAILABLE). 커밋 전 발송은 특히 결제 확정
 * 경로에서 위험하다 — 만료 sweep에 져 롤백되는 경로가 실제로 존재한다(TS-011).
 *
 * <p><b>왜 아웃박스가 아닌가.</b> 반드시 도달해야 하는 이벤트(order.paid)는 아웃박스로
 * 같은 커밋에 적재한다(ADR-010). 반면 여기서 다루는 좌석맵·주문상태 갱신 알림은 화면 최신화를
 * 앞당기는 보조 수단이고, 놓쳐도 폴링·재조회가 메운다(ADR-008, TS-012). 가장 뜨거운 경로인
 * 좌석 선점마다 DB 쓰기를 하나 더 얹을 만한 가치는 없다고 판단했다. 즉 <b>유실 허용치가
 * 다르므로 수단도 다르다</b>.
 *
 * <p>트랜잭션이 없으면(스케줄러 밖 호출·Kafka 소비 등) 그 자리에서 바로 실행한다.
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
