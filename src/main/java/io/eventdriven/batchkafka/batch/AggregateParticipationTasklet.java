package io.eventdriven.batchkafka.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 참여 이력 집계 Tasklet
 * - participation_history → campaign_stats 집계
 * - 일자별, 캠페인별 성공/실패 건수 통계
 */
@Slf4j
class AggregateParticipationTasklet implements Tasklet {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    AggregateParticipationTasklet(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> params = chunkContext.getStepContext().getJobParameters();

        try {
            // 1. 파라미터 파싱 및 검증
            LocalDateTime start;
            LocalDateTime end;

            if (params.containsKey("start") && params.containsKey("end")) {
                try {
                    start = LocalDateTime.parse(String.valueOf(params.get("start")));
                    end = LocalDateTime.parse(String.valueOf(params.get("end")));
                    log.info("🔄 집계 시작 - 기간: {} ~ {}", start, end);
                } catch (DateTimeParseException e) {
                    log.error("❌ 날짜 파싱 실패 - start: {}, end: {}",
                            params.get("start"), params.get("end"), e);
                    contribution.setExitStatus(ExitStatus.FAILED
                            .addExitDescription("날짜 형식 오류: " + e.getMessage()));
                    throw new IllegalArgumentException("날짜 형식이 올바르지 않습니다. ISO-8601 형식을 사용하세요.", e);
                }
            } else if (params.containsKey("date")) {
                try {
                    LocalDate date = LocalDate.parse(String.valueOf(params.get("date")));
                    start = date.atStartOfDay();
                    end = date.plusDays(1).atStartOfDay();
                    log.info("🔄 집계 시작 - 날짜: {}", date.format(DateTimeFormatter.ISO_DATE));
                } catch (DateTimeParseException e) {
                    log.error("❌ 날짜 파싱 실패 - date: {}", params.get("date"), e);
                    contribution.setExitStatus(ExitStatus.FAILED
                            .addExitDescription("날짜 형식 오류: " + e.getMessage()));
                    throw new IllegalArgumentException("날짜 형식이 올바르지 않습니다. YYYY-MM-DD 형식을 사용하세요.", e);
                }
            } else {
                log.error("❌ 필수 파라미터 누락 - params: {}", params);
                contribution.setExitStatus(ExitStatus.FAILED
                        .addExitDescription("필수 파라미터 누락"));
                throw new IllegalArgumentException(
                        "JobParameters required: either (start,end) as ISO-8601 datetime or (date) as YYYY-MM-DD");
            }

            // 2. 집계 대상 캠페인 ID 목록 조회 (대용량 처리 최적화)
            String campaignIdsSql = """
                    SELECT DISTINCT campaign_id
                    FROM participation_history
                    WHERE created_at >= :start AND created_at < :end
                    ORDER BY campaign_id
                    """;

            MapSqlParameterSource campaignIdsParams = new MapSqlParameterSource()
                    .addValue("start", start)
                    .addValue("end", end);

            List<Long> campaignIds = jdbcTemplate.queryForList(
                    campaignIdsSql,
                    campaignIdsParams,
                    Long.class
            );

            if (campaignIds.isEmpty()) {
                log.warn("⚠️ 집계 대상 데이터 없음 - 기간: {} ~ {}", start, end);
                contribution.setExitStatus(new ExitStatus("UPDATED_0")
                        .addExitDescription("집계 대상 데이터 없음"));
                return RepeatStatus.FINISHED;
            }

            log.info("📊 집계 대상 캠페인: {} 개 - {}", campaignIds.size(), campaignIds);

            // 3. 캠페인별 순차 집계 (작은 트랜잭션으로 분할)
            int totalUpdated = 0;
            int successCount = 0;
            int failureCount = 0;

            for (Long campaignId : campaignIds) {
                try {
                    int updated = aggregateByCampaign(campaignId, start, end);
                    totalUpdated += updated;
                    successCount++;
                    log.debug("  ✓ 캠페인 {} 집계 완료 - {} 행 업데이트", campaignId, updated);
                } catch (DataAccessException e) {
                    failureCount++;
                    log.error("  ✗ 캠페인 {} 집계 실패", campaignId, e);
                    // 개별 캠페인 실패는 로깅만 하고 계속 진행
                }
            }

            log.info("✅ 전체 집계 완료 - 성공: {}/{}, 실패: {}, 총 업데이트: {} 행",
                    successCount, campaignIds.size(), failureCount, totalUpdated);

            if (failureCount > 0) {
                log.warn("⚠️ 일부 캠페인 집계 실패 - 실패 수: {}", failureCount);
            }

            int updated = totalUpdated;

            // 3. 성공 상태 기록
            contribution.setExitStatus(new ExitStatus("UPDATED_" + updated));
            return RepeatStatus.FINISHED;

        } catch (DateTimeParseException e) {
            // 날짜 파싱 오류 (이미 위에서 처리했지만 안전장치)
            log.error("❌ 날짜 파싱 오류", e);
            contribution.setExitStatus(ExitStatus.FAILED
                    .addExitDescription("날짜 파싱 오류: " + e.getMessage()));
            throw new IllegalArgumentException("날짜 형식 오류", e);

        } catch (DataAccessException e) {
            // DB 접근 오류 (쿼리 실행 실패, 연결 끊김 등)
            log.error("❌ DB 접근 오류 발생 - 집계 실패", e);
            contribution.setExitStatus(ExitStatus.FAILED
                    .addExitDescription("DB 오류: " + e.getMessage()));
            throw e; // 트랜잭션 롤백을 위해 재throw

        } catch (IllegalArgumentException e) {
            // 파라미터 검증 실패
            log.error("❌ 잘못된 파라미터", e);
            contribution.setExitStatus(ExitStatus.FAILED
                    .addExitDescription("파라미터 오류: " + e.getMessage()));
            throw e;

        } catch (Exception e) {
            // 예상치 못한 오류
            log.error("❌ 예상치 못한 오류 발생", e);
            contribution.setExitStatus(ExitStatus.FAILED
                    .addExitDescription("예상치 못한 오류: " + e.getMessage()));
            throw new RuntimeException("집계 중 예상치 못한 오류가 발생했습니다.", e);
        }
    }

    /**
     * 캠페인별 집계 실행
     * - 작은 단위로 트랜잭션 분할하여 성능 최적화
     * - 개별 캠페인 실패 시에도 다른 캠페인 집계 계속 진행
     *
     * @param campaignId 캠페인 ID
     * @param start 집계 시작 시간
     * @param end 집계 종료 시간
     * @return 업데이트된 행 수
     */
    private int aggregateByCampaign(Long campaignId, LocalDateTime start, LocalDateTime end) {
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

        return jdbcTemplate.update(sql, params);
    }
}

