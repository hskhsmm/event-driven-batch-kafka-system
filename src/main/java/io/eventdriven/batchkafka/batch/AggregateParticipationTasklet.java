package io.eventdriven.batchkafka.batch;

import io.eventdriven.batchkafka.application.service.CampaignAggregationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.dao.DataAccessException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 참여 이력 집계 Tasklet
 * - participation_history → campaign_stats 집계
 * - 일자별, 캠페인별 성공/실패 건수 통계
 * - GROUP BY를 활용한 단일 쿼리로 N+1 문제 해결
 */
@Slf4j
class AggregateParticipationTasklet implements Tasklet {

    private final CampaignAggregationService campaignAggregationService;

    AggregateParticipationTasklet(CampaignAggregationService campaignAggregationService) {
        this.campaignAggregationService = campaignAggregationService;
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

            // 2. 모든 캠페인 일괄 집계 (단일 쿼리 - N+1 문제 해결)
            int updated = campaignAggregationService.aggregateAllCampaigns(start, end);

            if (updated == 0) {
                log.warn("⚠️ 집계 대상 데이터 없음 - 기간: {} ~ {}", start, end);
                contribution.setExitStatus(new ExitStatus("UPDATED_0")
                        .addExitDescription("집계 대상 데이터 없음"));
                return RepeatStatus.FINISHED;
            }

            log.info("✅ 전체 집계 완료 - {} 개 캠페인 업데이트 (단일 쿼리)", updated);

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
}

