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
 * 실시간 지표(admitExp 원소 수)는 스크랩 사이(15초)에 생겼다 사라진 상태를 보지 못한다. 토큰별로 "언제 들어와 언제
 * 나갔나"(승격 → 회수·이탈)를 시각으로 남겨야 시점별 슬롯 점유 수를 다시 셀 수 있다. 입장 게이트는 admitExp에 있는
 * 토큰만 통과시키므로(QueueService.isAdmitted), 회수·이탈 시각에 유효 입장도 끝난다.
 *
 * 승격 기록에는 진입 순번(seq)도 남긴다. 대기열 순서 위반(§3.3 사후)을 대조하는 근거다.
 *
 * 승격·회수·이탈만 남기고 발급은 남기지 않는다. 발급은 진입 수만큼(시험에서는 초당 수천 건) 생겨 측정 대상에
 * 로그 부하를 얹는다. 메타 만료 계산에 필요한 발급 시각은 발생기 쪽 기록(SSE 발생기의 연결 시작 시각)으로 갈음한다.
 * 승격 기록의 양은 입장한 사용자 수만큼이다.
 * 토큰 원문은 남기지 않는다 — 입장 권한과 묶인 값이라 로그에 두면 그 자체로 입장 수단이 된다.
 * 재구성에는 같은 토큰끼리 묶을 수 있으면 충분해서 해시 앞부분만 쓴다.
 */
final class QueueAudit {

    private static final Logger log = LoggerFactory.getLogger("flowticket.queue.audit");

    private QueueAudit() {}

    /**
     * 승격. 두 시각을 남긴다 — 승격 스크립트가 반환된 시각(admitExp로 통과하기 시작, 유효 구간의 시작)과
     * admit 키를 쓴 시각(키 TTL 시작, admit 키는 이 시각 + admitTtl까지 유효). 둘 사이에는 앞 토큰들의
     * admit 키 기록이 끼어 있어 간격이 생긴다.
     */
    static void admitted(Long eventId, String token, String seq, long admittedAtMillis, long keyWrittenAtMillis,
                         long admitExpiresAtEpochSec, long admitTtlSec) {
        // seq(진입 순번)는 대기열 순서 위반을 사후에 대조하는 근거다(loadtest-100k-plan §3.3).
        log.info("queue.audit kind=admit event={} token={} seq={} at={} keyAt={} admitExpAt={} admitKeyTtl={}",
                eventId, ref(token), seq, admittedAtMillis, keyWrittenAtMillis, admitExpiresAtEpochSec, admitTtlSec);
    }

    /**
     * 만료 회수. admitExp에서 빠지는 이 시각에 슬롯 점유와 유효 입장이 끝난다. admit 키는 지우지 않고 TTL까지 남지만,
     * 입장 게이트는 admitExp에 없는 토큰을 통과시키지 않는다(QueueService.isAdmitted).
     */
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
