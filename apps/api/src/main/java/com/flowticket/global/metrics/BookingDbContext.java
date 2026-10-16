package com.flowticket.global.metrics;

/**
 * 지금 요청 스레드가 어떤 예매 동작(BookingDbOp)을 처리 중인지 담는다.
 *
 * Hibernate는 세션 이벤트 리스너를 세션마다 리플렉션으로 만든다(hibernate.session.events.auto) —
 * 스프링 빈이 아니어서 요청 정보를 주입받을 수 없다. 그래서 요청 스레드에 동작 이름을 걸어 두고,
 * 리스너가 세션을 만들 때 읽는다. 트랜잭션은 요청 스레드에서 열리므로(open-in-view false) 같은 스레드다.
 */
public final class BookingDbContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private BookingDbContext() {
    }

    public static String current() {
        return CURRENT.get();
    }

    public static void set(String op) {
        CURRENT.set(op);
    }

    /** 요청이 끝나면 반드시 지운다. 톰캣 스레드는 재사용돼 남은 값이 다음 요청에 붙는다. */
    public static void clear() {
        CURRENT.remove();
    }
}
