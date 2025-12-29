package io.eventdriven.batchkafka.application.consumer;

import tools.jackson.databind.json.JsonMapper;
import io.eventdriven.batchkafka.api.exception.business.CampaignNotFoundException;
import io.eventdriven.batchkafka.api.exception.infrastructure.DatabaseException;
import io.eventdriven.batchkafka.api.exception.infrastructure.KafkaConsumeException;
import io.eventdriven.batchkafka.application.event.ParticipationEvent;
import io.eventdriven.batchkafka.domain.entity.Campaign;
import io.eventdriven.batchkafka.domain.entity.ParticipationHistory;
import io.eventdriven.batchkafka.domain.entity.ParticipationStatus;
import io.eventdriven.batchkafka.domain.repository.CampaignRepository;
import io.eventdriven.batchkafka.domain.repository.ParticipationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 선착순 참여 이벤트 Consumer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParticipationEventConsumer {

    private final JsonMapper jsonMapper;
    private final CampaignRepository campaignRepository;
    private final ParticipationHistoryRepository participationHistoryRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private static final String DLQ_TOPIC = "campaign-participation-topic.dlq";
    private static final int MAX_RETRIES = 3;

    @KafkaListener(
            topics = "campaign-participation-topic",
            groupId = "campaign-participation-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeParticipationEvent(String message, Acknowledgment acknowledgment) {
        int retryCount = 0;

        while (retryCount < MAX_RETRIES) {
            try {
                log.info("📨 Kafka 메시지 수신 (시도 {}/{}): {}", retryCount + 1, MAX_RETRIES, message);

                // 1. JSON 파싱
                ParticipationEvent event = parseMessage(message);
                log.info("✅ JSON 파싱 성공 - Campaign ID: {}, User ID: {}",
                        event.getCampaignId(), event.getUserId());

                // 2. 비즈니스 로직 실행
                processParticipation(event);

                // 3. 성공 시 커밋 후 반환
                acknowledgment.acknowledge();
                log.info("✅ 메시지 처리 완료 및 커밋 - Campaign ID: {}, User ID: {}",
                        event.getCampaignId(), event.getUserId());
                return;

            } catch (IllegalArgumentException e) {
                // JSON 파싱 오류 또는 비즈니스 로직 오류 = 영구 오류 (재시도 불필요)
                log.error("❌ JSON/비즈니스 오류 - DLQ로 전송: {}", message, e);
                sendToDlq(message, "JSON_OR_BUSINESS_ERROR", e);
                acknowledgment.acknowledge();  // 원본 큐에서는 제거
                return;

            } catch (CampaignNotFoundException e) {
                // 캠페인 없음 = 영구 오류 (재시도 불필요)
                log.error("❌ 캠페인 없음 - DLQ로 전송: {}", message, e);
                sendToDlq(message, "CAMPAIGN_NOT_FOUND", e);
                acknowledgment.acknowledge();
                return;

            } catch (DataAccessException e) {
                // DB 오류 = 임시 오류 (재시도 가능)
                retryCount++;
                if (retryCount < MAX_RETRIES) {
                    log.warn("⚠️ DB 오류 발생 - 재시도 {}/{}: {}", retryCount, MAX_RETRIES, e.getMessage());
                    try {
                        Thread.sleep(1000L * retryCount);  // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("재시도 대기 중 인터럽트 발생", ie);
                        break;
                    }
                } else {
                    log.error("❌ 최대 재시도 횟수 초과 - DLQ로 전송: {}", message, e);
                    sendToDlq(message, "MAX_RETRIES_EXCEEDED", e);
                    acknowledgment.acknowledge();
                    return;
                }

            } catch (Exception e) {
                // 예상치 못한 오류
                log.error("🚨 예상치 못한 오류 발생 - DLQ로 전송: {}", message, e);
                sendToDlq(message, "UNKNOWN_ERROR", e);
                acknowledgment.acknowledge();
                return;
            }
        }
    }

    /**
     * 메시지 파싱
     */
    private ParticipationEvent parseMessage(String message) {
        try {
            return jsonMapper.readValue(message, ParticipationEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 파싱 실패: " + message, e);
        }
    }

    /**
     * 참여 처리 비즈니스 로직
     */
    private void processParticipation(ParticipationEvent event) {
        // 1. 원자적 재고 차감 (반환값: 0=재고 부족, 1=성공)
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

        // 2. 참여 이력 저장
        Campaign campaign = campaignRepository.findById(event.getCampaignId())
                .orElseThrow(() -> new CampaignNotFoundException(event.getCampaignId()));
        ParticipationHistory history = new ParticipationHistory(campaign, event.getUserId(), status);
        participationHistoryRepository.save(history);
    }

    /**
     * Dead Letter Queue로 메시지 전송
     */
    private void sendToDlq(String originalMessage, String errorReason, Exception exception) {
        try {
            Map<String, String> dlqMessage = new HashMap<>();
            dlqMessage.put("originalMessage", originalMessage);
            dlqMessage.put("errorReason", errorReason);
            dlqMessage.put("errorMessage", exception.getMessage());
            dlqMessage.put("errorType", exception.getClass().getSimpleName());
            dlqMessage.put("timestamp", LocalDateTime.now().toString());

            String dlqPayload = jsonMapper.writeValueAsString(dlqMessage);
            kafkaTemplate.send(DLQ_TOPIC, dlqPayload);

            log.info("📤 DLQ 전송 완료 - 사유: {}, 토픽: {}", errorReason, DLQ_TOPIC);

            // 운영자 알림 (실제 환경에서는 Slack, Email 등으로 전송)
            log.error("🔔 [ALERT] DLQ 메시지 발생 - 확인 필요! 사유: {}", errorReason);

        } catch (Exception e) {
            log.error("🚨 DLQ 전송 실패 - 원본 메시지: {}", originalMessage, e);
            log.error("🔔 [CRITICAL ALERT] DLQ 전송 실패 - 즉시 확인 필요!");
            // TODO: 별도 로깅 시스템 또는 모니터링 시스템으로 전송
        }
    }
}
