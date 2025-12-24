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
 * Kafka Consumer: 선착순 참여 이벤트 처리
 *
 * 역할:
 * 1. Kafka Topic에서 JSON 메시지를 수신
 * 2. Campaign 재고 확인 및 차감 (원자적 연산)
 * 3. ParticipationHistory 저장 (성공/실패 기록)
 * 4. 수동 커밋으로 메시지 처리 보장
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

            // JSON 문자열 → ParticipationEvent 객체로 변환
            ParticipationEvent event = objectMapper.readValue(message, ParticipationEvent.class);
            log.info("✅ JSON 파싱 성공 - Campaign ID: {}, User ID: {}",
                    event.getCampaignId(), event.getUserId());

            // Campaign 조회
            Campaign campaign = campaignRepository.findById(event.getCampaignId())
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 캠페인입니다."));

            // 재고 확인 및 차감
            ParticipationStatus status;
            if (campaign.getCurrentStock() > 0) {
                campaign.decreaseStock(); // 재고 차감
                status = ParticipationStatus.SUCCESS;
                log.info("🎉 선착순 참여 성공 - User ID: {}, 남은 재고: {}",
                        event.getUserId(), campaign.getCurrentStock());
            } else {
                status = ParticipationStatus.FAIL;
                log.warn("❌ 선착순 마감 - User ID: {}, 재고 소진", event.getUserId());
            }

            // 참여 이력 저장
            ParticipationHistory history = new ParticipationHistory(
                    campaign,
                    event.getUserId(),
                    status
            );
            participationHistoryRepository.save(history);

            // 메시지 처리 완료 - 수동 커밋
            acknowledgment.acknowledge();
            log.info("✅ 메시지 처리 완료 및 커밋");

        } catch (Exception e) {
            log.error("🚨 메시지 처리 중 오류 발생: {}", e.getMessage(), e);
            // 오류 발생 시 커밋하지 않음 → 재처리 가능
        }
    }
}
