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
}
