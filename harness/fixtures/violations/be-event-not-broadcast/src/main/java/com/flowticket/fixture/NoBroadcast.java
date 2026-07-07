package com.flowticket.fixture;

/**
 * 위반 격리 fixture: events.yaml의 implemented 이벤트(seat.held 등)를
 * 백엔드가 발행해야 하는데, 이 소스엔 어떤 이벤트 발행 문자열도 없다.
 * → 하네스 섹션 13(구현 이벤트 발행 검증)이 실패를 잡아야 정상.
 */
public class NoBroadcast {
    public String hello() {
        return "no events broadcast here";
    }
}
