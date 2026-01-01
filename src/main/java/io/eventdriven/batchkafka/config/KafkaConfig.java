package io.eventdriven.batchkafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
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
     * 토픽 자동 생성 설정 제거
     * - 이유: 파티션 수를 동적으로 관리하기 위해 KafkaTopicService에서 처리
     * - KafkaTopicService.ensurePartitions()에서 토픽 생성 및 파티션 증가를 처리
     */
    // @Bean 제거: 동적 파티션 관리를 위해 토픽 자동 생성 비활성화
    // public NewTopic campaignParticipationTopic() {
    //     return TopicBuilder.name(TOPIC_NAME)
    //             .partitions(1)
    //             .replicas(1)
    //             .build();
    // }

    /**
     * Kafka Consumer 설정
     * - Key: String
     * - Value: String (JSON 문자열)
     * - 순서 보장을 위해 단일 Consumer로 설정
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, "campaign-participation-group");
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // 메시지 순서 보장을 위한 설정
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1); // 한 번에 1개씩 처리
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // 수동 커밋 (처리 완료 후 커밋)

        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(1); // 단일 Consumer 스레드 (순서 보장)
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL); // 수동 커밋
        return factory;
    }
}
