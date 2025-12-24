package io.eventdriven.batchkafka.application.event;

public class ParticipationEvent {
    private Long campaignId;
    private Long userId;

    public ParticipationEvent() {
    }

    public ParticipationEvent(Long campaignId, Long userId) {
        this.campaignId = campaignId;
        this.userId = userId;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public Long getUserId() {
        return userId;
    }
}
