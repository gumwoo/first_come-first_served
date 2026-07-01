package com.flowticket.queue.dto;

/** 대기 상태 폴링 응답. rank/total은 WAITING일 때 유효, etaSeconds는 추정치. */
public record QueueStatusResponse(long rank, long total, long etaSeconds, String status) {}
