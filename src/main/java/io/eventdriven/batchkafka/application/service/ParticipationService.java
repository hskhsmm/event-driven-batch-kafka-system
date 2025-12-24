package io.eventdriven.batchkafka.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ParticipationService {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public void participate(Long campaignId, Long userId) {
        // 메시지 포맷: "campaignId:userId"
        // 파티션 키를 campaignId로 설정하여 순서 보장 (같은 캠페인은 같은 파티션으로)
        String key = String.valueOf(campaignId);
        String message = campaignId + ":" + userId;

        kafkaTemplate.send("campaign-participation-topic", key, message);
    }
}
