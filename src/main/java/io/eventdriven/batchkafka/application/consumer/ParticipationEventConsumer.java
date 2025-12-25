package io.eventdriven.batchkafka.application.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.eventdriven.batchkafka.application.event.ParticipationEvent;
import io.eventdriven.batchkafka.domain.entity.Campaign;
import io.eventdriven.batchkafka.domain.entity.ParticipationHistory;
import io.eventdriven.batchkafka.domain.entity.ParticipationStatus;
import io.eventdriven.batchkafka.domain.repository.CampaignRepository;
import io.eventdriven.batchkafka.domain.repository.ParticipationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선착순 참여 이벤트 Consumer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParticipationEventConsumer {

    private final ObjectMapper objectMapper;
    private final CampaignRepository campaignRepository;
    private final ParticipationHistoryRepository participationHistoryRepository;

    @KafkaListener(
            topics = "campaign-participation-topic",
            groupId = "campaign-participation-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeParticipationEvent(String message, Acknowledgment acknowledgment) {
        try {
            log.info("📨 Kafka 메시지 수신: {}", message);

            ParticipationEvent event = objectMapper.readValue(message, ParticipationEvent.class);
            log.info("✅ JSON 파싱 성공 - Campaign ID: {}, User ID: {}",
                    event.getCampaignId(), event.getUserId());

            // 원자적 재고 차감 (반환값: 0=재고 부족, 1=성공)
            int updatedRows = campaignRepository.decreaseStockAtomic(event.getCampaignId());

            ParticipationStatus status;
            if (updatedRows > 0) {
                status = ParticipationStatus.SUCCESS;
                log.info("🎉 선착순 참여 성공 - User ID: {}, Campaign ID: {}",
                        event.getUserId(), event.getCampaignId());
            } else {
                status = ParticipationStatus.FAIL;
                log.warn("❌ 선착순 마감 - User ID: {}, Campaign ID: {}, 사유: 재고 부족",
                        event.getUserId(), event.getCampaignId());
            }

            // 참여 이력 저장
            Campaign campaign = campaignRepository.findById(event.getCampaignId())
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 캠페인입니다."));
            ParticipationHistory history = new ParticipationHistory(campaign, event.getUserId(), status);
            participationHistoryRepository.save(history);

            acknowledgment.acknowledge();
            log.info("✅ 메시지 처리 완료 및 커밋");

        } catch (Exception e) {
            log.error("🚨 메시지 처리 중 오류 발생: {}", e.getMessage(), e);
        }
    }
}
