package io.eventdriven.batchkafka.application.service;

import io.eventdriven.batchkafka.application.event.ParticipationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 부하 테스트용 서비스
 * - 대량의 Kafka 메시지를 발행하여 선착순 시스템 동작 테스트
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipationTestService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    private static final String TOPIC = "campaign-participation-topic";

    // 테스트용 고유 사용자 ID를 만들기 위한 AtomicLong
    // System.currentTimeMillis()로 초기화하여 매번 다른 ID 생성
    private final AtomicLong userIdCounter = new AtomicLong(System.currentTimeMillis());

    /**
     * 대량 참여 시뮬레이션
     *
     * @param campaignId 캠페인 ID
     * @param count 발행할 메시지 수
     */
    @Async  // 비동기 실행: HTTP 응답이 지연되지 않도록 함
    public void simulate(Long campaignId, int count) {
        log.info("📊 시뮬레이션 시작 - 캠페인 ID: {}, 총 {:,}건", campaignId, count);

        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < count; i++) {
            try {
                // 각 요청마다 고유한 사용자 ID 생성
                long userId = userIdCounter.getAndIncrement();

                // ParticipationEvent 객체 생성
                ParticipationEvent event = new ParticipationEvent(campaignId, userId);

                // JSON으로 직렬화
                String message = jsonMapper.writeValueAsString(event);

                // Kafka에 메시지 발행 (비동기)
                kafkaTemplate.send(TOPIC, String.valueOf(campaignId), message);

                successCount++;

                // 진행 상황 로그 (1000건마다)
                if ((i + 1) % 1000 == 0) {
                    log.info("📤 {:,} / {:,} 건 발행 완료 ({:.1f}%)",
                            (i + 1), count, ((i + 1) * 100.0 / count));
                }

            } catch (Exception e) {
                failCount++;
                log.error("❌ 테스트 메시지 발행 실패 (userId: {})", userIdCounter.get(), e);
            }
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (count * 1000.0) / duration;  // 초당 처리량

        log.info("✅ 시뮬레이션 완료!");
        log.info("   - 총 요청: {:,}건", count);
        log.info("   - 성공: {:,}건", successCount);
        log.info("   - 실패: {:,}건", failCount);
        log.info("   - 소요 시간: {:,}ms ({:.2f}초)", duration, duration / 1000.0);
        log.info("   - 처리량: {:.0f} 건/초", throughput);
    }
}
