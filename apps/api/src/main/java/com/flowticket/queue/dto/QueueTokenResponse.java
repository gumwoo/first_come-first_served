package com.flowticket.queue.dto;

/**
 * 대기 진입 응답. status는 QueueStatus 이름.
 * retryAfterMs: 첫 상태 조회까지 최소 대기(ms) — 진입 burst 직후 전원이 같은 시각에 조회하지 않게 서버가 순번으로 정한다.
 */
public record QueueTokenResponse(String token, String status, long rank, long total, long retryAfterMs) {}
