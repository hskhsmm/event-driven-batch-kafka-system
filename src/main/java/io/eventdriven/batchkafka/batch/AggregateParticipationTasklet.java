package io.eventdriven.batchkafka.batch;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

class AggregateParticipationTasklet implements Tasklet {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    AggregateParticipationTasklet(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> params = chunkContext.getStepContext().getJobParameters();

        LocalDateTime start;
        LocalDateTime end;

        if (params.containsKey("start") && params.containsKey("end")) {
            start = LocalDateTime.parse(String.valueOf(params.get("start")));
            end = LocalDateTime.parse(String.valueOf(params.get("end")));
        } else if (params.containsKey("date")) {
            LocalDate date = LocalDate.parse(String.valueOf(params.get("date")));
            start = date.atStartOfDay();
            end = date.plusDays(1).atStartOfDay();
        } else {
            throw new IllegalArgumentException("JobParameters required: either (start,end) as ISO-8601 datetime or (date) as YYYY-MM-DD");
        }

        String sql = "INSERT INTO campaign_stats (campaign_id, success_count, fail_count, stats_date)\n" +
                "SELECT p.campaign_id,\n" +
                "       SUM(CASE WHEN p.status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count,\n" +
                "       SUM(CASE WHEN p.status = 'FAIL' THEN 1 ELSE 0 END)    AS fail_count,\n" +
                "       DATE(p.created_at)                                   AS stats_date\n" +
                "FROM participation_history p\n" +
                "WHERE p.created_at >= :start AND p.created_at < :end\n" +
                "GROUP BY p.campaign_id, DATE(p.created_at)\n" +
                "ON DUPLICATE KEY UPDATE\n" +
                "  success_count = VALUES(success_count),\n" +
                "  fail_count    = VALUES(fail_count)";

        MapSqlParameterSource ps = new MapSqlParameterSource()
                .addValue("start", start)
                .addValue("end", end);

        int updated = jdbcTemplate.update(sql, ps);

        // record in exit status for logs/inspection
        contribution.setExitStatus(new ExitStatus("UPDATED_" + updated));
        return RepeatStatus.FINISHED;
    }
}

