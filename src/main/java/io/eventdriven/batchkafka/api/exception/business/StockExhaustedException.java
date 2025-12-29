package io.eventdriven.batchkafka.api.exception.business;

import io.eventdriven.batchkafka.api.exception.common.BusinessException;
import io.eventdriven.batchkafka.api.exception.common.ErrorCode;

/**
 * 선착순 재고가 소진되었을 때 발생하는 예외
 * HTTP 409 Conflict
 */
public class StockExhaustedException extends BusinessException {

    public StockExhaustedException(Long campaignId) {
        super(ErrorCode.STOCK_EXHAUSTED,
              String.format("선착순 수량이 모두 소진되었습니다. (캠페인 ID: %d)", campaignId));
    }

    public StockExhaustedException() {
        super(ErrorCode.STOCK_EXHAUSTED);
    }
}
