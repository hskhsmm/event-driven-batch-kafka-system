package io.eventdriven.batchkafka.application.service;

import tools.jackson.databind.json.JsonMapper;
import io.eventdriven.batchkafka.api.exception.business.CampaignNotFoundException;
import io.eventdriven.batchkafka.api.exception.infrastructure.KafkaPublishException;
import io.eventdriven.batchkafka.api.exception.infrastructure.KafkaSerializationException;
import io.eventdriven.batchkafka.application.event.ParticipationEvent;
import io.eventdriven.batchkafka.domain.entity.Campaign;
import io.eventdriven.batchkafka.domain.entity.ParticipationHistory;
import io.eventdriven.batchkafka.domain.entity.ParticipationStatus;
import io.eventdriven.batchkafka.domain.repository.CampaignRepository;
import io.eventdriven.batchkafka.domain.repository.ParticipationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipationService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final RedisStockService redisStockService;
    private final CampaignRepository campaignRepository;
    private final ParticipationHistoryRepository participationHistoryRepository;

    private static final String TOPIC = "campaign-participation-topic";

    /**
     * 선착순 참여 요청 처리
     *
     * 흐름:
     * 1. Redis Lua 원자적 재고 차감 (선착순 판별)
     *    - 실패 시 즉시 "마감됐습니다" 반환 (Kafka 발행 안 함)
     * 2. PENDING 상태로 participation_history DB 저장 (선착순 자리 확보)
     * 3. Kafka 발행 (Consumer가 PENDING → SUCCESS 확정)
     *
     * 이렇게 하는 이유:
     * - 재고 차감을 Consumer에서 하면 서버 다운 후 재처리 시 중복 차감 발생
     * - API 단에서 차감하면 Kafka 메시지는 이미 선착순 통과된 것만 존재
     * - PENDING이 DB에 먼저 박히므로 서버 다운 후 재시작해도 자리 보장
     */
    @Transactional
    public boolean participate(Long campaignId, Long userId) {
        // 1. Redis 원자적 재고 차감 (선착순 판별)
        Long remainingStock = redisStockService.decreaseStock(campaignId);

        if (remainingStock < 0) {
            // 재고 없음 → 즉시 실패 반환 (Kafka 발행 안 함)
            log.info(" 재고 소진 - Campaign: {}, User: {}", campaignId, userId);
            return false;
        }

        // 2. PENDING 상태로 DB 선점 (선착순 자리 확보)
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));

        ParticipationHistory history = new ParticipationHistory(campaign, userId, ParticipationStatus.PENDING);
        participationHistoryRepository.save(history);
        log.info("PENDING 저장 - Campaign: {}, User: {}, HistoryId: {}", campaignId, userId, history.getId());

        // 3. Kafka 발행 (Consumer가 PENDING → SUCCESS 확정)
        publishToKafka(campaignId, userId);

        return true;
    }

    /**
     * Kafka 이벤트 발행
     */
    private void publishToKafka(Long campaignId, Long userId) {
        ParticipationEvent event = new ParticipationEvent(campaignId, userId);

        try {
            String message = jsonMapper.writeValueAsString(event);

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(TOPIC, null, message);

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    handleKafkaPublishFailure(campaignId, userId, message, ex);
                } else {
                    handleKafkaPublishSuccess(campaignId, userId, result);
                }
            });

        } catch (Exception e) {
            log.error("JSON 직렬화 실패 - Campaign ID: {}, User ID: {}", campaignId, userId, e);
            throw new KafkaSerializationException(e);
        }
    }

    /**
     * Kafka 전송 성공 처리
     */
    private void handleKafkaPublishSuccess(Long campaignId, Long userId, SendResult<String, String> result) {
        log.info("Kafka 전송 성공 - Campaign ID: {}, User ID: {}, Offset: {}, Partition: {}",
                campaignId,
                userId,
                result.getRecordMetadata().offset(),
                result.getRecordMetadata().partition());
    }

    /**
     * Kafka 전송 실패 처리
     */
    private void handleKafkaPublishFailure(Long campaignId, Long userId, String message, Throwable ex) {
        log.error("Kafka 전송 실패 - Campaign ID: {}, User ID: {}, Message: {}",
                campaignId, userId, message, ex);
        log.error("[ALERT] Kafka 전송 실패 - 데이터 손실 위험! Campaign ID: {}, User ID: {}",
                campaignId, userId);
        throw new KafkaPublishException(TOPIC, ex);
    }
}
