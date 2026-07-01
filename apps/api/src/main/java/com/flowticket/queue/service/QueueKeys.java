package com.flowticket.queue.service;

/** 대기열 Redis 키 규약(단일 출처). */
final class QueueKeys {

    private QueueKeys() {}

    static final String ACTIVE_EVENTS = "queue:active-events";

    static String wait(Long eventId)        { return "queue:wait:" + eventId; }
    static String seq(Long eventId)         { return "queue:seq:" + eventId; }
    static String admitCount(Long eventId)  { return "queue:admitcount:" + eventId; }
    static String admitExp(Long eventId)    { return "queue:admitexp:" + eventId; }
    static String user(Long eventId, Long userId) { return "queue:user:" + eventId + ":" + userId; }
    static String token(String token)       { return "queue:token:" + token; }
    static String admit(String token)       { return "queue:admit:" + token; }
}
