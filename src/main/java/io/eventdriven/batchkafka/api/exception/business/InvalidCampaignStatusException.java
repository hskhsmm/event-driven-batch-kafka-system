package io.eventdriven.batchkafka.api.exception.business;

import io.eventdriven.batchkafka.api.exception.common.BusinessException;
import io.eventdriven.batchkafka.api.exception.common.ErrorCode;

/**
 * 캠페인 상태가 유효하지 않을 때 발생하는 예외
 * HTTP 400 Bad Request
 */
public class InvalidCampaignStatusException extends BusinessException {

    public InvalidCampaignStatusException(String status) {
        super(ErrorCode.INVALID_CAMPAIGN_STATUS,
              String.format("유효하지 않은 캠페인 상태입니다. (상태: %s)", status));
    }

    public InvalidCampaignStatusException() {
        super(ErrorCode.INVALID_CAMPAIGN_STATUS);
    }
}
