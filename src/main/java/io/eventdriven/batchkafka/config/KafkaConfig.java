package io.eventdriven.batchkafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    public static final String TOPIC_NAME = "campaign-participation-topic";

    /**
     * Kafka Producer 설정
     * - Key: String (Campaign ID 등)
     * - Value: String (JSON 문자열)
     * - 신뢰성 설정 추가 (acks=all, idempotence=true)
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // 신뢰성 및 순서 보장을 위한 설정
        configProps.put(ProducerConfig.ACKS_CONFIG, "all"); // 모든 리플리카 승인 대기
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // 멱등성 보장 (중복 방지)
        configProps.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE); // 실패 시 무한 재시도

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    /**
     * 토픽 자동 생성 설정
     * - partitions: 1 (선착순 순서 보장을 위해 파티션 1개 설정)
     * - replicas: 1 (로컬 개발 환경이므로 1개)
     */
    @Bean
    public NewTopic campaignParticipationTopic() {
        return TopicBuilder.name(TOPIC_NAME)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
