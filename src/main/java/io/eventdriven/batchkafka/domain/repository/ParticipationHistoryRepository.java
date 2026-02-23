package io.eventdriven.batchkafka.domain.repository;

import io.eventdriven.batchkafka.domain.entity.ParticipationHistory;
import io.eventdriven.batchkafka.domain.entity.ParticipationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ParticipationHistoryRepository extends JpaRepository<ParticipationHistory, Long> {

    /**
     * 캠페인별 상태별 건수 조회 (실시간 현황 API용)
     */
    Long countByCampaignIdAndStatus(Long campaignId, ParticipationStatus status);

    /**
     * 캠페인별 성공 건수 조회
     */
    @Query("""
        SELECT COUNT(ph)
        FROM ParticipationHistory ph
        WHERE ph.campaign.id = :campaignId AND ph.status = 'SUCCESS'
    """)
    Long countSuccessByCampaignId(@Param("campaignId") Long campaignId);

    /**
     * 캠페인별 실패 건수 조회
     */
    @Query("""
        SELECT COUNT(ph)
        FROM ParticipationHistory ph
        WHERE ph.campaign.id = :campaignId AND ph.status = 'FAIL'
    """)
    Long countFailByCampaignId(@Param("campaignId") Long campaignId);

    /**
     * 캠페인별 참여 이력 조회 (Kafka 메시지 생성 시간 순서대로, 순서 분석용)
     * kafka_timestamp 순서로 정렬하여 실제 메시지 생성 순서를 확인
     */
    @Query("""
        SELECT ph
        FROM ParticipationHistory ph
        WHERE ph.campaign.id = :campaignId AND ph.kafkaTimestamp IS NOT NULL
        ORDER BY ph.kafkaTimestamp ASC
    """)
    List<ParticipationHistory> findByCampaignIdOrderByKafkaTimestampAsc(@Param("campaignId") Long campaignId);

    /**
     * 캠페인별 최근 참여 이력 조회 (실시간 처리 성능 측정용)
     * 지정된 시간 이후의 데이터를 생성 시간 순서대로 조회
     */
    List<ParticipationHistory> findByCampaignIdAndCreatedAtAfterOrderByCreatedAtAsc(Long campaignId, LocalDateTime createdAt);

    /**
     * 중복 메시지 체크 (멱등성 보장)
     * kafka_partition + kafka_offset 조합은 Kafka에서 전역 유일 보장
     * Consumer 재처리 시 이미 처리된 메시지 스킵용
     */
    boolean existsByKafkaPartitionAndKafkaOffset(Integer kafkaPartition, Long kafkaOffset);

    /**
     * PENDING 상태 레코드 조회 (Consumer에서 확정 처리용)
     * API 단에서 저장한 PENDING 레코드를 찾아 SUCCESS/FAIL로 업데이트
     */
    Optional<ParticipationHistory> findByCampaignIdAndUserIdAndStatus(
            Long campaignId, Long userId, ParticipationStatus status
    );

    /**
     * 참여 결과 조회 (사용자 결과 확인 API용)
     * 가장 최근 레코드 기준으로 조회
     */
    Optional<ParticipationHistory> findTopByCampaignIdAndUserIdOrderByCreatedAtDesc(
            Long campaignId, Long userId
    );
}
