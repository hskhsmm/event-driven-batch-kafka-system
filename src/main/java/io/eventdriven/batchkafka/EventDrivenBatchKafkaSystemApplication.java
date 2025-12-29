package io.eventdriven.batchkafka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync  // 비동기 메서드 실행 활성화 (부하 테스트용)
public class EventDrivenBatchKafkaSystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(EventDrivenBatchKafkaSystemApplication.class, args);
	}

}
