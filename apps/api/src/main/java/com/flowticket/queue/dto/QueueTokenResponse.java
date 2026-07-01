package com.flowticket.queue.dto;

/** 대기 진입 응답. status는 QueueStatus 이름. */
public record QueueTokenResponse(String token, String status, long rank, long total) {}
