package io.eventdriven.batchkafka.api.dto.response;

import io.eventdriven.batchkafka.domain.entity.Campaign;
import io.eventdriven.batchkafka.domain.entity.CampaignStatus;
import lombok.Getter;

@Getter
public class CampaignResponse {
    private Long id;
    private String name;
    private Long totalStock;
    private Long currentStock;
    private CampaignStatus status;

    public CampaignResponse(Campaign campaign) {
        this.id = campaign.getId();
        this.name = campaign.getName();
        this.totalStock = campaign.getTotalStock();
        this.currentStock = campaign.getCurrentStock();
        this.status = campaign.getStatus();
    }
}
