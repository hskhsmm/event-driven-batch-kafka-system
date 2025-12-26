package io.eventdriven.batchkafka.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

/**
 * Spring Batch 메타데이터 정리 Tasklet
 * - 오래된 배치 실행 이력 삭제
 * - BATCH_JOB_EXECUTION, BATCH_STEP_EXECUTION 등 정리
 */
@Slf4j
@RequiredArgsConstructor
class BatchMetadataCleanupTasklet implements Tasklet {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 보관 기간 (일)
     * - 90일 이상 오래된 메타데이터 삭제
     */
    private static final int RETENTION_DAYS = 90;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        try {
            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(RETENTION_DAYS);
            log.info("🗑️ 배치 메타데이터 정리 시작 - 기준일: {} ({} 일 이전)",
                    cutoffDate, RETENTION_DAYS);

            int totalDeleted = 0;

            // 1. BATCH_JOB_EXECUTION_PARAMS 삭제
            String deleteParams = """
                    DELETE FROM BATCH_JOB_EXECUTION_PARAMS
                    WHERE JOB_EXECUTION_ID IN (
                        SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION
                        WHERE CREATE_TIME < ?
                    )
                    """;
            int deletedParams = jdbcTemplate.update(deleteParams, cutoffDate);
            log.debug("  ✓ BATCH_JOB_EXECUTION_PARAMS: {} 건 삭제", deletedParams);
            totalDeleted += deletedParams;

            // 2. BATCH_STEP_EXECUTION_CONTEXT 삭제
            String deleteStepContext = """
                    DELETE FROM BATCH_STEP_EXECUTION_CONTEXT
                    WHERE STEP_EXECUTION_ID IN (
                        SELECT STEP_EXECUTION_ID FROM BATCH_STEP_EXECUTION
                        WHERE JOB_EXECUTION_ID IN (
                            SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION
                            WHERE CREATE_TIME < ?
                        )
                    )
                    """;
            int deletedStepContext = jdbcTemplate.update(deleteStepContext, cutoffDate);
            log.debug("  ✓ BATCH_STEP_EXECUTION_CONTEXT: {} 건 삭제", deletedStepContext);
            totalDeleted += deletedStepContext;

            // 3. BATCH_STEP_EXECUTION 삭제
            String deleteStepExecution = """
                    DELETE FROM BATCH_STEP_EXECUTION
                    WHERE JOB_EXECUTION_ID IN (
                        SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION
                        WHERE CREATE_TIME < ?
                    )
                    """;
            int deletedStepExecution = jdbcTemplate.update(deleteStepExecution, cutoffDate);
            log.debug("  ✓ BATCH_STEP_EXECUTION: {} 건 삭제", deletedStepExecution);
            totalDeleted += deletedStepExecution;

            // 4. BATCH_JOB_EXECUTION_CONTEXT 삭제
            String deleteJobContext = """
                    DELETE FROM BATCH_JOB_EXECUTION_CONTEXT
                    WHERE JOB_EXECUTION_ID IN (
                        SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION
                        WHERE CREATE_TIME < ?
                    )
                    """;
            int deletedJobContext = jdbcTemplate.update(deleteJobContext, cutoffDate);
            log.debug("  ✓ BATCH_JOB_EXECUTION_CONTEXT: {} 건 삭제", deletedJobContext);
            totalDeleted += deletedJobContext;

            // 5. BATCH_JOB_EXECUTION 삭제
            String deleteJobExecution = """
                    DELETE FROM BATCH_JOB_EXECUTION
                    WHERE CREATE_TIME < ?
                    """;
            int deletedJobExecution = jdbcTemplate.update(deleteJobExecution, cutoffDate);
            log.debug("  ✓ BATCH_JOB_EXECUTION: {} 건 삭제", deletedJobExecution);
            totalDeleted += deletedJobExecution;

            log.info(" 배치 메타데이터 정리 완료 - 총 {} 건 삭제", totalDeleted);

            contribution.setExitStatus(new ExitStatus("DELETED_" + totalDeleted));
            return RepeatStatus.FINISHED;

        } catch (DataAccessException e) {
            log.error(" 메타데이터 정리 중 DB 오류 발생", e);
            contribution.setExitStatus(ExitStatus.FAILED
                    .addExitDescription("DB 오류: " + e.getMessage()));
            throw e;

        } catch (Exception e) {
            log.error(" 메타데이터 정리 중 예상치 못한 오류 발생", e);
            contribution.setExitStatus(ExitStatus.FAILED
                    .addExitDescription("예상치 못한 오류: " + e.getMessage()));
            throw new RuntimeException("메타데이터 정리 중 오류 발생", e);
        }
    }
}
