package com.flowticket.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 배경 스케줄러 활성화. <b>끌 수 있어야 해서</b> 애플리케이션 클래스에서 분리했다.
 *
 * <p>통합테스트는 스케줄러가 돌면 안 된다. 스윕이 자기 마음대로 좌석·주문을 건드리면 검증이
 * 비결정적이 되고, 무엇보다 <b>테스트 초기화의 TRUNCATE와 데드락을 만든다</b> —
 * TRUNCATE는 ACCESS EXCLUSIVE, 스윕의 UPDATE는 RowExclusive를 잡고 서로를 문다.
 * 2026-08-06 CI 실패 6회차에서 실제로 확인된 원인이다.
 *
 * <p><b>주기를 크게 잡는 것으로는 못 막는다.</b> {@code @Scheduled(fixedRateString = ...)}에는
 * {@code initialDelay}가 없어 <b>컨텍스트가 뜨는 순간 첫 실행이 즉시 발사</b>되기 때문이다.
 * 주기를 1시간으로 늘려도 미뤄지는 것은 <b>두 번째 실행뿐</b>이다. 컨텍스트가 여러 개인 스위트에서는
 * 새 컨텍스트가 뜰 때마다 스윕이 한 번씩 발사되고, 그게 다른 테스트의 초기화와 겹친다.
 *
 * <p>그래서 주기가 아니라 <b>스케줄링 자체를</b> 끈다. 스윕 동작이 필요한 테스트는 서비스 메서드를
 * 직접 호출해 결정적으로 검증한다(현재 전부 그렇게 하고 있다).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "flowticket.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
