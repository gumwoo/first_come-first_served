package com.flowticket.queue.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 입장 슬롯의 생애(승격·회수·이탈)를 남긴다. 실효 입장 초과를 사후에 재구성하는 근거다(loadtest-100k-plan §3.3).
 *
 * 실시간 지표(admitExp 원소 수)는 입장 게이트의 admit 키 폴백을 보지 못한다. 회수 후에도 admit 키는
 * 승격 시각 + TTL까지 살아 있으므로, 토큰별로 "언제 들어와 언제까지 유효했나"를 시각으로 남겨야
 * 시점별 유효 입장 수를 다시 셀 수 있다.
 *
 * 양은 정원 단위다. 승격은 빈 슬롯만큼만 일어나므로 대기자 수와 무관하다.
 * 토큰 원문은 남기지 않는다 — 입장 권한과 묶인 값이라 로그에 두면 그 자체로 입장 수단이 된다.
 * 재구성에는 같은 토큰끼리 묶을 수 있으면 충분해서 해시 앞부분만 쓴다.
 */
final class QueueAudit {

    private static final Logger log = LoggerFactory.getLogger("flowticket.queue.audit");

    private QueueAudit() {}

    /** 승격 확정 후 admit 키를 쓴 시각. admit 키는 이 시각 + admitTtl까지 유효하다. */
    static void admitted(Long eventId, String token, long atMillis, long admitExpiresAtEpochSec, long admitTtlSec) {
        log.info("queue.audit kind=admit event={} token={} at={} admitExpAt={} admitKeyTtl={}",
                eventId, ref(token), atMillis, admitExpiresAtEpochSec, admitTtlSec);
    }

    /** 만료 회수. admitExp에서는 빠졌지만 admit 키는 지우지 않는다(키 TTL까지 유효). */
    static void reclaimed(Long eventId, String token, long atMillis) {
        log.info("queue.audit kind=reclaim event={} token={} at={}", eventId, ref(token), atMillis);
    }

    /** 입장 상태에서 이탈. admitExp와 admit 키를 함께 지우므로 이 시각에 유효 입장이 끝난다. */
    static void leftAdmitted(Long eventId, String token, long atMillis) {
        log.info("queue.audit kind=leave event={} token={} at={}", eventId, ref(token), atMillis);
    }

    static String ref(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 없음", e); // JDK 표준 알고리즘이라 일어나지 않는다
        }
    }
}
