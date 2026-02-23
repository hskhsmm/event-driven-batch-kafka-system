package io.eventdriven.batchkafka.domain.entity;

public enum ParticipationStatus {
    PENDING,  // API 단에서 선착순 자리 확보 후 Consumer 확정 전 중간 상태
    SUCCESS,
    FAIL
}
