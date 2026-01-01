package io.eventdriven.batchkafka.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LoadTestRequest {

    @NotNull(message = "캠페인 ID는 필수입니다")
    private Long campaignId;

    @Min(value = 1, message = "가상 사용자 수는 1 이상이어야 합니다")
    private Integer virtualUsers = 100; // 기본값 100

    @Min(value = 1, message = "테스트 지속 시간은 1초 이상이어야 합니다")
    private Integer duration = 5; // 기본값 5초
}
