package io.eventdriven.batchkafka.api.exception.infrastructure;

import io.eventdriven.batchkafka.api.exception.common.ErrorCode;
import io.eventdriven.batchkafka.api.exception.common.InfrastructureException;

/**
 * 데이터베이스 오류 발생 시 발생하는 예외
 * HTTP 500 Internal Server Error
 */
public class DatabaseException extends InfrastructureException {

    public DatabaseException(String message, Throwable cause) {
        super(ErrorCode.DATABASE_ERROR, message, cause);
    }

    public DatabaseException(Throwable cause) {
        super(ErrorCode.DATABASE_ERROR, cause);
    }

    public DatabaseException(String message) {
        super(ErrorCode.DATABASE_ERROR, message);
    }
}
