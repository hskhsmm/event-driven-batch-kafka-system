package io.eventdriven.batchkafka.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreatePartitionsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.TopicDescription;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Kafka 토픽 파티션 동적 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaTopicService {

    private final KafkaAdmin kafkaAdmin;
    private static final String TOPIC_NAME = "campaign-participation-topic";

    /**
     * 토픽의 파티션 수를 확인하고, 필요하면 늘림
     *
     * @param desiredPartitions 원하는 파티션 수
     * @return 실제 적용된 파티션 수
     */
    public int ensurePartitions(int desiredPartitions) {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {

            // 1. 현재 파티션 수 확인
            int currentPartitions = getCurrentPartitionCount(adminClient);
            log.info("📊 현재 파티션 수: {}, 요청된 파티션 수: {}", currentPartitions, desiredPartitions);

            // 2. 파티션이 부족하면 늘림
            if (desiredPartitions > currentPartitions) {
                increasePartitions(adminClient, desiredPartitions);
                log.info("✅ 파티션 증가 완료: {} → {}", currentPartitions, desiredPartitions);
                return desiredPartitions;
            } else if (desiredPartitions < currentPartitions) {
                log.warn("⚠️ 파티션 감소는 지원되지 않습니다. 현재: {}, 요청: {}", currentPartitions, desiredPartitions);
                return currentPartitions;
            } else {
                log.info("ℹ️ 파티션 수가 이미 적절합니다: {}", currentPartitions);
                return currentPartitions;
            }

        } catch (Exception e) {
            log.error("❌ 파티션 관리 중 오류 발생", e);
            // 오류 발생 시 기본값 반환 (테스트는 계속 진행)
            return 1;
        }
    }

    /**
     * 현재 토픽의 파티션 수 조회
     */
    private int getCurrentPartitionCount(AdminClient adminClient) throws ExecutionException, InterruptedException {
        DescribeTopicsResult describeResult = adminClient.describeTopics(Collections.singletonList(TOPIC_NAME));

        // topicNameValues() 메서드 사용
        TopicDescription description = describeResult.topicNameValues().get(TOPIC_NAME).get();

        if (description == null) {
            throw new IllegalStateException("토픽이 존재하지 않습니다: " + TOPIC_NAME);
        }

        return description.partitions().size();
    }

    /**
     * 파티션 수 증가
     */
    private void increasePartitions(AdminClient adminClient, int newPartitionCount) throws ExecutionException, InterruptedException {
        Map<String, NewPartitions> newPartitions = Collections.singletonMap(
                TOPIC_NAME,
                NewPartitions.increaseTo(newPartitionCount)
        );

        CreatePartitionsResult result = adminClient.createPartitions(newPartitions);
        result.all().get();  // 완료될 때까지 대기
    }
}
