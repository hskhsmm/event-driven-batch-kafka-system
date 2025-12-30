package io.eventdriven.batchkafka.domain.repository;

import io.eventdriven.batchkafka.domain.entity.ParticipationHistory;
import io.eventdriven.batchkafka.domain.entity.ParticipationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParticipationHistoryRepository extends JpaRepository<ParticipationHistory, Long> {

    /**
     * 캠페인별 상태별 건수 조회 (실시간 현황 API용)
     */
    Long countByCampaignIdAndStatus(Long campaignId, ParticipationStatus status);

    /**
     * 캠페인별 성공/실패 건수 한 번에 조회 (더 효율적)
     */
    @Query("""
        SELECT
            COALESCE(SUM(CASE WHEN ph.status = 'SUCCESS' THEN 1 ELSE 0 END), 0) as successCount,
            COALESCE(SUM(CASE WHEN ph.status = 'FAIL' THEN 1 ELSE 0 END), 0) as failCount
        FROM ParticipationHistory ph
        WHERE ph.campaign.id = :campaignId
    """)
    Object[] countStatusByCampaignId(@Param("campaignId") Long campaignId);
}
