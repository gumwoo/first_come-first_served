package com.flowticket.queue.dto;

/**
 * 대기 상태 폴링 응답. rank/total은 WAITING일 때 유효, etaSeconds는 추정치.
 * retryAfterMs: 다음 조회까지 최소 대기(ms) — 이보다 일찍 다시 묻지 않는다. 입장·만료(종료 상태)면 0.
 */
public record QueueStatusResponse(long rank, long total, long etaSeconds, String status, long retryAfterMs) {}
