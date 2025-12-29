package io.eventdriven.batchkafka.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 캠페인 집계 서비스
 * - 개별 캠페인별 통계 집계를 독립적인 트랜잭션으로 처리
 * - REQUIRES_NEW: 각 캠페인 집계가 독립적인 트랜잭션으로 실행되어 일부 실패 시에도 다른 캠페인은 정상 처리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignAggregationService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * 캠페인별 집계 실행 (독립 트랜잭션)
     * - 각 캠페인별로 새로운 트랜잭션 생성
     * - 개별 캠페인 실패 시 다른 캠페인에 영향 없음
     * - 멱등성 보장: ON DUPLICATE KEY UPDATE 사용
     *
     * @param campaignId 캠페인 ID
     * @param start 집계 시작 시간
     * @param end 집계 종료 시간
     * @return 업데이트된 행 수 (1 or 0)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public int aggregateByCampaign(Long campaignId, LocalDateTime start, LocalDateTime end) {
        String sql = """
                INSERT INTO campaign_stats (campaign_id, success_count, fail_count, stats_date)
                SELECT :campaignId,
                       SUM(CASE WHEN p.status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count,
                       SUM(CASE WHEN p.status = 'FAIL' THEN 1 ELSE 0 END)    AS fail_count,
                       DATE(:start)                                          AS stats_date
                FROM participation_history p
                WHERE p.campaign_id = :campaignId
                  AND p.created_at >= :start
                  AND p.created_at < :end
                ON DUPLICATE KEY UPDATE
                  success_count = VALUES(success_count),
                  fail_count    = VALUES(fail_count)
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("campaignId", campaignId)
                .addValue("start", start)
                .addValue("end", end);

        int updated = jdbcTemplate.update(sql, params);

        log.debug("✓ 캠페인 {} 집계 완료 - {} 행 업데이트", campaignId, updated);

        return updated;
    }
}
