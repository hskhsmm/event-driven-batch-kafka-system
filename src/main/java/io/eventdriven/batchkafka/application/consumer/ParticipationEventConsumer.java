package io.eventdriven.batchkafka.application.consumer;

import tools.jackson.databind.json.JsonMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 선착순 참여 이벤트 Consumer
 *
 * 역할 변경:
 * - 기존: Redis 재고 차감 + DB 신규 저장
 * - 변경: API 단에서 저장된 PENDING 레코드를 SUCCESS/FAIL로 확정만 처리
 *
 * 이렇게 바꾼 이유:
 * - 재고 차감이 API 단(Redis)으로 이동했으므로 Consumer에서 차감하면 중복 차감 발생
 * - 서버 다운 후 재처리 시에도 PENDING 레코드를 찾아 업데이트할 뿐 재고에 영향 없음
 * - update 방식이라 같은 메시지가 두 번 와도 자연스럽게 멱등성 보장
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParticipationEventConsumer {

    private final JsonMapper jsonMapper;
    private final ParticipationHistoryRepository participationHistoryRepository;
    private final CampaignRepository campaignRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ProcessingLogService processingLogService;

    private static final String DLQ_TOPIC = "campaign-participation-topic.dlq";
    private static final int LOG_INTERVAL = 10000;

    private long processedCount = 0;
    private long successCount = 0;
    private long failCount = 0;
    private long skipCount = 0; // 중복 메시지 스킵 카운터

    @KafkaListener(
            topics = "campaign-participation-topic",
            groupId = "campaign-participation-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeParticipationEvent(List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) {
        log.info("Kafka 배치 수신. 사이즈: {}건", records.size());

        try {
            for (ConsumerRecord<String, String> record : records) {
                processRecord(record);
            }
            acknowledgment.acknowledge();
            log.info("배치 처리 완료 및 커밋. 사이즈: {}건", records.size());

        } catch (Exception e) {
            log.error("배치 처리 중 심각한 오류 발생. 배치 전체(총 {}건)를 DLQ로 전송합니다.", records.size(), e);
            sendBatchToDlq(records, "BATCH_PROCESSING_ERROR", e);
            acknowledgment.acknowledge();
        }
    }

    private void processRecord(ConsumerRecord<String, String> record) {
        String message = record.value();

        try {
            // 1. 중복 메시지 체크 (멱등성 보장)
            // partition + offset 조합은 Kafka에서 전역 유일 보장
            // 서버 다운 후 재처리 시 이미 처리된 메시지 스킵
            if (participationHistoryRepository.existsByKafkaPartitionAndKafkaOffset(
                    record.partition(), record.offset())) {
                log.warn("중복 메시지 스킵 - partition: {}, offset: {}", record.partition(), record.offset());
                skipCount++;
                return;
            }

            // 2. JSON 파싱
            ParticipationEvent event = parseMessage(message);

            // 3. PENDING 레코드 찾아서 SUCCESS로 확정
            // API 단에서 이미 Redis 차감 + PENDING 저장이 완료된 상태
            // Consumer는 확정 역할만 담당
            confirmPendingRecord(event, record);

        } catch (IllegalArgumentException e) {
            log.error("복구 불가능한 메시지 오류 - DLQ로 전송: {}", message, e);
            sendToDlq(message, e.getClass().getSimpleName(), e);
            throw e;
        }
    }

    /**
     * PENDING → SUCCESS 확정 처리
     *
     * PENDING 레코드가 없는 경우:
     * - API 단에서 저장 전에 Kafka 메시지가 먼저 도착한 엣지 케이스
     * - DLQ로 보내지 않고 로그만 남김 (재처리 시 PENDING이 생길 수 있음)
     */
    private void confirmPendingRecord(ParticipationEvent event, ConsumerRecord<String, String> record) {
        Optional<ParticipationHistory> pendingHistory = participationHistoryRepository
                .findByCampaignIdAndUserIdAndStatus(
                        event.getCampaignId(),
                        event.getUserId(),
                        ParticipationStatus.PENDING
                );

        if (pendingHistory.isEmpty()) {
            log.warn("PENDING 레코드 없음 - Campaign: {}, User: {}, partition: {}, offset: {}",
                    event.getCampaignId(), event.getUserId(), record.partition(), record.offset());
            return;
        }

        ParticipationHistory history = pendingHistory.get();

        // PENDING → SUCCESS 확정
        history.updateStatus(ParticipationStatus.SUCCESS);

        // Kafka 메타데이터 업데이트 (추적용)
        history.updateKafkaMetadata(record.partition(), record.offset(), record.timestamp());

        // Campaign DB current_stock 동기화 (페이지 재고 표시용)
        campaignRepository.findById(event.getCampaignId()).ifPresent(Campaign::decreaseStock);

        log.info("확정 완료 - Campaign: {}, User: {}, HistoryId: {}",
                event.getCampaignId(), event.getUserId(), history.getId());

        updateCountersAndLog(event, ParticipationStatus.SUCCESS);
    }

    private ParticipationEvent parseMessage(String message) {
        try {
            return jsonMapper.readValue(message, ParticipationEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 파싱 실패: " + message, e);
        }
    }

    private synchronized void updateCountersAndLog(ParticipationEvent event, ParticipationStatus status) {
        processedCount++;

        if (status == ParticipationStatus.SUCCESS) {
            successCount++;
        } else {
            failCount++;
        }

        if (processedCount % LOG_INTERVAL == 0) {
            String logMessage = String.format(
                    "[Kafka Consumer] 처리: %,d건 | 성공: %,d | 실패: %,d | 중복스킵: %,d | Campaign=%d, User=%d",
                    processedCount, successCount, failCount, skipCount,
                    event.getCampaignId(), event.getUserId()
            );
            processingLogService.info(logMessage);
            log.info(logMessage);
        }
    }

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
            log.info("DLQ 전송 완료 - 사유: {}", errorReason);
        } catch (Exception e) {
            log.error("CRITICAL: DLQ 전송 실패! 원본 메시지: {}", originalMessage, e);
        }
    }

    private void sendBatchToDlq(List<ConsumerRecord<String, String>> records, String errorReason, Exception exception) {
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
            log.info("배치 DLQ 전송 완료 - 사유: {}", errorReason);
        } catch (Exception e) {
            log.error("CRITICAL: 배치 DLQ 전송 실패!", e);
            for (String msg : originalMessages) {
                log.error("배치 DLQ 실패 개별 메시지: {}", msg);
            }
        }
    }
}
