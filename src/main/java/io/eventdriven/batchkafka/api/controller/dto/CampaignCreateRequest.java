package io.eventdriven.batchkafka.api.controller.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CampaignCreateRequest {
    private String name;
    private Long totalStock;

    public CampaignCreateRequest(String name, Long totalStock) {
        this.name = name;
        this.totalStock = totalStock;
    }
}
