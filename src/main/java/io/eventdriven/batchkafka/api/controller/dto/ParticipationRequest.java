package io.eventdriven.batchkafka.api.controller.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ParticipationRequest {

    @NotNull(message = "사용자 ID는 필수입니다.")
    private Long userId;
}
