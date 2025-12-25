package io.eventdriven.batchkafka.application.service;

import tools.jackson.databind.json.JsonMapper;
import io.eventdriven.batchkafka.application.event.ParticipationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ParticipationService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    public void participate(Long campaignId, Long userId) {
        ParticipationEvent event = new ParticipationEvent(campaignId, userId);

        try {
            // 객체를 JSON 문자열로 변환
            String message = jsonMapper.writeValueAsString(event);

            // Kafka 전송 (Key: campaignId -> 순서 보장)
            kafkaTemplate.send("campaign-participation-topic", String.valueOf(campaignId), message);

        } catch (Exception e) {
            throw new RuntimeException("이벤트 직렬화 중 오류 발생", e);
        }
    }
}