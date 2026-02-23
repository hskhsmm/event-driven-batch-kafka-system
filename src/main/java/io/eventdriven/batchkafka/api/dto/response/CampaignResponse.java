package io.eventdriven.batchkafka.api.dto.response;

import io.eventdriven.batchkafka.domain.entity.Campaign;
import io.eventdriven.batchkafka.domain.entity.CampaignStatus;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class CampaignResponse {
    private Long id;
    private String name;
    private Long totalStock;
    private Long currentStock;
    private CampaignStatus status;
    private LocalDateTime createdAt;  // 프론트엔드 CampaignManagement.tsx에서 테이블에 표시

    public CampaignResponse(Campaign campaign) {
        this.id = campaign.getId();
        this.name = campaign.getName();
        this.totalStock = campaign.getTotalStock();
        this.currentStock = campaign.getCurrentStock();
        this.status = campaign.getStatus();
        this.createdAt = campaign.getCreatedAt();
    }

    // Redis 실시간 재고 우선 반영용
    public CampaignResponse(Campaign campaign, Long redisStock) {
        this(campaign);
        if (redisStock != null) {
            this.currentStock = redisStock;
        }
    }
}
