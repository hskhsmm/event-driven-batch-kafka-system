package io.eventdriven.batchkafka.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson ObjectMapper 명시적 Bean 등록
 *
 * <p>Spring Boot 4.0.1부터는 ObjectMapper가 자동으로 등록되지 않는 경우가 있어
 * Kafka 메시지 직렬화/역직렬화를 위해 명시적으로 Bean을 선언합니다.
 *
 * <p>특히 Kafka/Batch 중심 프로젝트에서는 Web starter가 있어도
 * JSON 사용 여부를 명시적으로 선언하는 것이 Spring Boot 4의 권장 패턴입니다.
 */
@Configuration
public class JacksonConfig {

    /**
     * ObjectMapper Bean 생성
     *
     * <p>JsonMapper.builder()를 사용하여 Spring Boot 4 권장 방식으로 생성하며,
     * findAndAddModules()를 통해 Java 8 날짜/시간 타입 등의 모듈을 자동으로 추가합니다.
     *
     * @return JSON 직렬화/역직렬화에 사용할 ObjectMapper 인스턴스
     */
    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .findAndAddModules()  // Java 8 날짜/시간, Parameter names 등 모듈 자동 등록
                .build();
    }
}
