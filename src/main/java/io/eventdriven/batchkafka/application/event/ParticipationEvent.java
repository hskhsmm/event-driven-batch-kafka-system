package io.eventdriven.batchkafka.application.event;

public class ParticipationEvent {
    private Long campaignId;
    private Long userId;

    // Kafka 메타데이터 (Consumer에서 설정)
    private Long kafkaOffset;
    private Integer kafkaPartition;
    private Long kafkaTimestamp;

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

    public Long getKafkaOffset() {
        return kafkaOffset;
    }

    public void setKafkaOffset(Long kafkaOffset) {
        this.kafkaOffset = kafkaOffset;
    }

    public Integer getKafkaPartition() {
        return kafkaPartition;
    }

    public void setKafkaPartition(Integer kafkaPartition) {
        this.kafkaPartition = kafkaPartition;
    }

    public Long getKafkaTimestamp() {
        return kafkaTimestamp;
    }

    public void setKafkaTimestamp(Long kafkaTimestamp) {
        this.kafkaTimestamp = kafkaTimestamp;
    }
}
