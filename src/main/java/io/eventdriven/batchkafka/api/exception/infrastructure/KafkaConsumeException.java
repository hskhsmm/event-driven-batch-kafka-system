package io.eventdriven.batchkafka.api.exception.infrastructure;

import io.eventdriven.batchkafka.api.exception.common.ErrorCode;
import io.eventdriven.batchkafka.api.exception.common.InfrastructureException;

/**
 * Kafka 메시지 소비 실패 시 발생하는 예외
 * HTTP 500 Internal Server Error
 */
public class KafkaConsumeException extends InfrastructureException {

    public KafkaConsumeException(String message, Throwable cause) {
        super(ErrorCode.KAFKA_CONSUME_FAILED, message, cause);
    }

    public KafkaConsumeException(Throwable cause) {
        super(ErrorCode.KAFKA_CONSUME_FAILED, cause);
    }
}
