package io.eventdriven.batchkafka.application.consumer;

import tools.jackson.core.JsonProcessingException;
import tools.jackson.databind.json.JsonMapper;
import io.eventdriven.batchkafka.api.exception.business.CampaignNotFoundException;
import io.eventdriven.batchkafka.application.event.ParticipationEvent;
import io.eventdriven.batchkafka.application.service.ProcessingLogService;
import io.eventdriven.batchkafka.domain.entity.Campaign;
import io.eventdriven.batchkafka.domain.entity.ParticipationHistory;
import io.eventdriven.batchkafka.domain.entity.ParticipationStatus;
import io.eventdriven.batchkafka.domain.repository.CampaignRepository;
import io.eventdriven.batchkafka.domain.repository.ParticipationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 선착순 참여 이벤트 Consumer (배치 처리 방식)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParticipationEventConsumer {

    private final JsonMapper jsonMapper;
    private final CampaignRepository campaignRepository;
    private final ParticipationHistoryRepository participationHistoryRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ProcessingLogService processingLogService;

    private static final String DLQ_TOPIC = "campaign-participation-topic.dlq";
    private static final int LOG_INTERVAL = 1000; // 1000건마다 로그

    // 처리 건수 카운터 (메모리 기반, 재시작 시 초기화)
    private long processedCount = 0;
    private long successCount = 0;
    private long failCount = 0;

    @KafkaListener(
            topics = "campaign-participation-topic",
            groupId = "campaign-participation-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeParticipationEvent(List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) {
        log.info("📨 Kafka 배치 수신. 사이즈: {}건", records.size());

        try {
            for (ConsumerRecord<String, String> record : records) {
                processRecord(record);
            }
            acknowledgment.acknowledge();
            log.info("✅ 배치 처리 완료 및 커밋. 사이즈: {}건", records.size());

        } catch (Exception e) {
            // @Transactional에 의해 롤백되므로, 여기서는 DLQ 전송 및 원본 메시지 커밋만 처리
            log.error("🚨 배치 처리 중 심각한 오류 발생. 배치 전체(총 {}건)를 DLQ로 전송합니다.", records.size(), e);
            sendBatchToDlq(records, "BATCH_PROCESSING_ERROR", e);
            acknowledgment.acknowledge(); // 오류 발생한 배치는 재처리하지 않도록 커밋
        }
    }

    /**
     * 단일 레코드 처리
     */
    private void processRecord(ConsumerRecord<String, String> record) {
        String message = record.value();
        try {
            // 1. JSON 파싱
            ParticipationEvent event = parseMessage(message);

            // 2. Kafka 메타데이터 설정
            event.setKafkaOffset(record.offset());
            event.setKafkaPartition(record.partition());
            event.setKafkaTimestamp(record.timestamp());

            // 3. 비즈니스 로직 실행
            ParticipationStatus status = processParticipation(event);

            // 4. 카운터 업데이트 및 로깅
            updateCountersAndLog(event, status);

        } catch (IllegalArgumentException | CampaignNotFoundException e) {
            // JSON 파싱 오류 또는 캠페인 없음 등 복구 불가능한 단일 메시지 오류
            log.error("❌ 복구 불가능한 메시지 오류 - DLQ로 전송: {}", message, e);
            sendToDlq(message, e.getClass().getSimpleName(), e);
            // 전체 배치를 중단시키지 않고 계속 진행. 트랜잭션은 롤백될 것임.
            // 하지만 이런 메시지가 있다면 전체 배치가 실패하게 되므로, 예외를 다시 던져서 롤백을 유도해야함.
            throw e;
        }
        // DataAccessException 등 다른 RuntimeException은 @Transactional에 의해 자동으로 롤백 처리됨
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
    private ParticipationStatus processParticipation(ParticipationEvent event) {
        // 1. 원자적 재고 차감 (반환값: 0=재고 부족, 1=성공)
        int updatedRows = campaignRepository.decreaseStockAtomic(event.getCampaignId());

        ParticipationStatus status;
        if (updatedRows > 0) {
            status = ParticipationStatus.SUCCESS;
        } else {
            status = ParticipationStatus.FAIL;
        }

        // 2. 참여 이력 저장 (Kafka 메타데이터 포함)
        Campaign campaign = campaignRepository.findById(event.getCampaignId())
                .orElseThrow(() -> new CampaignNotFoundException(event.getCampaignId()));
        ParticipationHistory history = new ParticipationHistory(
                campaign,
                event.getUserId(),
                status,
                event.getKafkaOffset(),
                event.getKafkaPartition(),
                event.getKafkaTimestamp()
        );
        participationHistoryRepository.save(history);

        return status;
    }
    
    /**
     * 카운터 업데이트 및 1000건마다 로그
     */
    private synchronized void updateCountersAndLog(ParticipationEvent event, ParticipationStatus status) {
        processedCount++;

        if (status == ParticipationStatus.SUCCESS) {
            successCount++;
        } else {
            failCount++;
        }

        if (processedCount % LOG_INTERVAL == 0) {
            String logMessage = String.format(
                    "[Kafka Consumer] 처리 건수: %,d건 | 성공: %,d | 실패: %,d | 최근 처리: Campaign=%d, User=%d, Partition=%d, Offset=%d",
                    processedCount, successCount, failCount,
                    event.getCampaignId(), event.getUserId(),
                    event.getKafkaPartition(), event.getKafkaOffset()
            );
            processingLogService.info(logMessage);
            log.info("📊 " + logMessage);
        }
    }

    /**
     * Dead Letter Queue로 단일 메시지 전송
     */
    private void sendToDlq(String originalMessage, String errorReason, Exception exception) {
        try {
            Map<String, Object> dlqMessage = new HashMap<>();
            dlqMessage.put("originalMessage", originalMessage);
            dlqMessage.put("errorReason", errorReason);
            dlqMessage.put("errorMessage", exception.getMessage());
            dlqMessage.put("errorType", exception.getClass().getSimpleName());
            dlqMessage.put("timestamp", LocalDateTime.now().toString());

            String dlqPayload = jsonMapper.writeValueAsString(dlqMessage);
            kafkaTemplate.send(DLQ_TOPIC, dlqPayload);
            log.info("📤 DLQ 전송 완료 - 사유: {}, 토픽: {}", errorReason, DLQ_TOPIC);
        } catch (Exception e) {
            log.error("🚨 CRITICAL: DLQ 전송 실패! 원본 메시지: {}", originalMessage, e);
        }
    }

    /**
     * Dead Letter Queue로 배치 메시지 전송
     */
    private void sendBatchToDlq(List<ConsumerRecord<String, String>> records, String errorReason, Exception exception) {
        log.info("배치 DLQ 전송 시작. 총 {}건", records.size());
        List<String> originalMessages = records.stream()
                                               .map(ConsumerRecord::value)
                                               .collect(Collectors.toList());
        try {
            Map<String, Object> dlqMessage = new HashMap<>();
            dlqMessage.put("originalMessages", originalMessages);
            dlqMessage.put("errorReason", errorReason);
            dlqMessage.put("errorMessage", exception.getMessage());
            dlqMessage.put("errorType", exception.getClass().getSimpleName());
            dlqMessage.put("batchSize", records.size());
            dlqMessage.put("timestamp", LocalDateTime.now().toString());

            String dlqPayload = jsonMapper.writeValueAsString(dlqMessage);
            kafkaTemplate.send(DLQ_TOPIC, dlqPayload);
            log.info("📤 배치 DLQ 전송 완료 - 사유: {}, 토픽: {}", errorReason, DLQ_TOPIC);
        } catch (Exception e) {
            log.error("🚨 CRITICAL: 배치 DLQ 전송 실패! 전체 메시지를 개별적으로 로깅합니다.", e);
            for (String msg : originalMessages) {
                log.error("배치 DLQ 실패 개별 메시지: {}", msg);
            }
        }
    }
}