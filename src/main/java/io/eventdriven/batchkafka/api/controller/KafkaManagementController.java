package io.eventdriven.batchkafka.api.controller;

import io.eventdriven.batchkafka.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Kafka Consumer 관리 API
 * - Consumer 재시작
 * - 파티션 설정 재감지
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/kafka")
@RequiredArgsConstructor
public class KafkaManagementController {

    private final KafkaListenerEndpointRegistry registry;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private static final String TOPIC_NAME = "campaign-participation-topic";

    /**
     * Consumer Container 재시작 + 파티션 설정 재감지
     * POST /api/admin/kafka/reload-consumers
     *
     * 사용법:
     * 1. Docker로 파티션 변경: docker exec kafka kafka-topics --bootstrap-server kafka:29092 --alter --topic campaign-participation-topic --partitions 3
     * 2. 이 API 호출: POST /api/admin/kafka/reload-consumers
     * 3. Consumer가 새로운 파티션 설정으로 재시작됨
     */
    @PostMapping("/reload-consumers")
    public ResponseEntity<ApiResponse<?>> reloadConsumers() {
        try {
            log.info("🔄 Consumer 재시작 요청 - 파티션 설정 재감지 시작");

            // 1. 현재 토픽의 파티션 수 조회
            int currentPartitionCount = getTopicPartitionCount(TOPIC_NAME);

            // 2. 모든 Consumer Container 중지
            Collection<MessageListenerContainer> containers = registry.getListenerContainers();
            log.info("⏸️ Consumer Container 중지 중... (총 {}개)", containers.size());

            for (MessageListenerContainer container : containers) {
                container.stop();
                log.info("⏸️ Container 중지: {}", container.getListenerId());
            }

            // 3. Concurrency 업데이트 (동적으로 변경)
            // 주의: Spring Kafka는 런타임에 concurrency를 변경하기 어려움
            // 대신 Container를 재시작하면 새로운 파티션에서 메시지를 가져옴
            log.info("🔧 현재 파티션 수: {} (Consumer는 자동으로 리밸런싱됨)", currentPartitionCount);

            // 4. Consumer Container 재시작
            log.info("▶️ Consumer Container 재시작 중...");
            for (MessageListenerContainer container : containers) {
                container.start();
                log.info("▶️ Container 재시작 완료: {} (concurrency는 변경되지 않지만 파티션 리밸런싱됨)", container.getListenerId());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("currentPartitionCount", currentPartitionCount);
            result.put("restartedContainers", containers.size());
            result.put("message", String.format(
                    "Consumer 재시작 완료. 현재 파티션 수: %d (파티션 리밸런싱 적용됨)",
                    currentPartitionCount
            ));
            result.put("warning", "⚠️ concurrency는 애플리케이션 재시작 시에만 변경됩니다. 현재 Consumer 스레드 수는 유지됩니다.");

            log.info("✅ Consumer 재시작 완료 - 파티션: {}", currentPartitionCount);

            return ResponseEntity.ok(ApiResponse.success(result));

        } catch (Exception e) {
            log.error("🚨 Consumer 재시작 실패", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.fail("Consumer 재시작 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    /**
     * 현재 토픽의 파티션 수 조회
     */
    @GetMapping("/partition-count")
    public ResponseEntity<ApiResponse<?>> getPartitionCount() {
        try {
            int partitionCount = getTopicPartitionCount(TOPIC_NAME);

            Map<String, Object> result = new HashMap<>();
            result.put("topic", TOPIC_NAME);
            result.put("partitionCount", partitionCount);

            return ResponseEntity.ok(ApiResponse.success(result));

        } catch (Exception e) {
            log.error("🚨 파티션 수 조회 실패", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.fail("파티션 수 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * Kafka 토픽의 파티션 수를 조회
     */
    private int getTopicPartitionCount(String topicName) {
        try {
            Map<String, Object> configs = new HashMap<>();
            configs.put("bootstrap.servers", bootstrapServers);

            try (AdminClient adminClient = AdminClient.create(configs)) {
                DescribeTopicsResult result = adminClient.describeTopics(Collections.singletonList(topicName));
                Map<String, TopicDescription> descriptions = result.allTopicNames().get(5, java.util.concurrent.TimeUnit.SECONDS);
                TopicDescription description = descriptions.get(topicName);

                if (description == null) {
                    log.warn("⚠️ 토픽 '{}' 을 찾을 수 없습니다.", topicName);
                    return 1;
                }

                int partitionCount = description.partitions().size();
                log.info("📊 토픽 '{}' 파티션 수: {}", topicName, partitionCount);

                return partitionCount;
            }
        } catch (Exception e) {
            log.error("🚨 파티션 수 조회 실패: {}", e.getMessage());
            return 1;
        }
    }
}
