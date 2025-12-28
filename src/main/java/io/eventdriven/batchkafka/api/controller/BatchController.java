package io.eventdriven.batchkafka.api.controller;

import io.eventdriven.batchkafka.api.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.*;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.launch.*;
import org.springframework.batch.core.repository.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Spring Batch 관리 API
 * - 집계 배치 실행, 이력 조회, 상태 조회
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/batch")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowCredentials = "true")
@SuppressWarnings("removal")
public class BatchController {

    @Qualifier("asyncJobLauncher")
    private final JobLauncher asyncJobLauncher;
    private final Job aggregateParticipationJob;
    private final JobExplorer jobExplorer;

    /**
     * 참여 이력 집계 배치 실행
     * POST /api/admin/batch/aggregate?date=2025-12-26
     */
    @PostMapping("/aggregate")
    public ResponseEntity<ApiResponse<?>> aggregate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        try {
            // 날짜 유효성 검증
            validateDate(date);

            JobParameters params = new JobParametersBuilder()
                    .addString("date", date.toString())
                    .addLong("ts", System.currentTimeMillis()) // ensure uniqueness
                    .toJobParameters();

            // 비동기 실행 (API 응답 즉시 반환)
            JobExecution exec = asyncJobLauncher.run(aggregateParticipationJob, params);

            log.info("✅ 집계 배치 실행 시작 - jobExecutionId: {}, date: {}",
                    exec.getId(), date);

            Map<String, Object> data = new HashMap<>();
            data.put("jobExecutionId", exec.getId());
            data.put("jobInstanceId", exec.getJobInstance().getInstanceId());
            data.put("status", exec.getStatus().toString());
            data.put("date", date.toString());

            return ResponseEntity.ok(
                    ApiResponse.success(
                            "배치 작업이 시작되었습니다. /api/admin/batch/status/" + exec.getId() + "에서 진행 상황을 확인하세요.",
                            data
                    )
            );

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ 배치 실행 실패 - 잘못된 파라미터: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail(e.getMessage()));

        } catch (JobExecutionAlreadyRunningException e) {
            log.warn("⚠️ 배치 실행 실패 - 이미 실행 중: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail("해당 배치 작업이 이미 실행 중입니다."));

        } catch (JobRestartException e) {
            log.error("🚨 배치 실행 실패 - 재시작 불가: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("배치 작업을 재시작할 수 없습니다: " + e.getMessage()));

        } catch (JobInstanceAlreadyCompleteException e) {
            log.warn("⚠️ 배치 실행 실패 - 이미 완료됨: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail("해당 날짜의 집계가 이미 완료되었습니다."));

        } catch (InvalidJobParametersException e) {
            log.error("🚨 배치 실행 실패 - 잘못된 파라미터: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail("잘못된 배치 파라미터: " + e.getMessage()));

        } catch (Exception e) {
            log.error("🚨 배치 실행 중 예상치 못한 오류 발생", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("배치 실행 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    /**
     * 배치 실행 상태 조회
     * GET /api/admin/batch/status/{jobExecutionId}
     */
    @GetMapping("/status/{jobExecutionId}")
    public ResponseEntity<ApiResponse<?>> getStatus(@PathVariable Long jobExecutionId) {
        try {
            JobExecution execution = jobExplorer.getJobExecution(jobExecutionId);

            if (execution == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.fail("배치 실행 정보를 찾을 수 없습니다."));
            }

            Map<String, Object> data = new HashMap<>();
            data.put("jobExecutionId", execution.getId());
            data.put("jobName", execution.getJobInstance().getJobName());
            data.put("status", execution.getStatus().toString());
            data.put("exitStatus", execution.getExitStatus().getExitCode());
            data.put("exitDescription", execution.getExitStatus().getExitDescription());
            data.put("startTime", execution.getStartTime());
            data.put("endTime", execution.getEndTime());
            data.put("createTime", execution.getCreateTime());

            // Job Parameters 추출
            try {
                String date = execution.getJobParameters().getString("date");
                data.put("targetDate", date);
            } catch (Exception e) {
                // date 파라미터가 없을 수 있음
            }

            // 실행 결과에서 업데이트된 행 수 추출
            String exitCode = execution.getExitStatus().getExitCode();
            if (exitCode != null && exitCode.startsWith("UPDATED_")) {
                String updatedCount = exitCode.substring("UPDATED_".length());
                data.put("updatedRows", updatedCount);
            }

            return ResponseEntity.ok(ApiResponse.success(data));

        } catch (Exception e) {
            log.error("🚨 배치 상태 조회 실패 - jobExecutionId: {}", jobExecutionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("배치 상태 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 배치 실행 이력 조회 (최근 20개)
     * GET /api/admin/batch/history?jobName=aggregateParticipation&size=20
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<?>> getHistory(
            @RequestParam(defaultValue = "aggregateParticipation") String jobName,
            @RequestParam(defaultValue = "20") int size
    ) {
        try {
            List<JobInstance> jobInstances = jobExplorer.getJobInstances(jobName, 0, size);

            List<Map<String, Object>> history = jobInstances.stream()
                    .map(instance -> {
                        List<JobExecution> executions = jobExplorer.getJobExecutions(instance);
                        JobExecution latestExecution = executions.isEmpty() ? null : executions.get(0);

                        Map<String, Object> item = new HashMap<>();
                        item.put("jobInstanceId", instance.getInstanceId());
                        item.put("jobName", instance.getJobName());

                        if (latestExecution != null) {
                            item.put("jobExecutionId", latestExecution.getId());
                            item.put("status", latestExecution.getStatus().toString());
                            item.put("exitStatus", latestExecution.getExitStatus().getExitCode());
                            item.put("startTime", latestExecution.getStartTime());
                            item.put("endTime", latestExecution.getEndTime());

                            // date 파라미터 추출
                            try {
                                String date = latestExecution.getJobParameters().getString("date");
                                item.put("targetDate", date);
                            } catch (Exception e) {
                                // ignore
                            }

                            // 업데이트된 행 수 추출
                            String exitCode = latestExecution.getExitStatus().getExitCode();
                            if (exitCode != null && exitCode.startsWith("UPDATED_")) {
                                item.put("updatedRows", exitCode.substring("UPDATED_".length()));
                            }
                        }

                        return item;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> data = new HashMap<>();
            data.put("jobName", jobName);
            data.put("totalCount", history.size());
            data.put("history", history);

            return ResponseEntity.ok(ApiResponse.success(data));

        } catch (Exception e) {
            log.error("🚨 배치 이력 조회 실패 - jobName: {}", jobName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("배치 이력 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 날짜 유효성 검증
     */
    private void validateDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("날짜는 필수 파라미터입니다.");
        }

        // 미래 날짜 체크
        if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException(
                    String.format("미래 날짜는 집계할 수 없습니다. (입력: %s, 현재: %s)",
                            date.format(DateTimeFormatter.ISO_DATE),
                            LocalDate.now().format(DateTimeFormatter.ISO_DATE))
            );
        }

        // 너무 오래된 날짜 체크 (1년 이상 과거)
        if (date.isBefore(LocalDate.now().minusYears(1))) {
            throw new IllegalArgumentException(
                    String.format("1년 이상 과거 날짜는 집계할 수 없습니다. (입력: %s)",
                            date.format(DateTimeFormatter.ISO_DATE))
            );
        }
    }
}

