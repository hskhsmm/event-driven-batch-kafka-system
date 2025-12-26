package io.eventdriven.batchkafka.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/batch")
@RequiredArgsConstructor
public class BatchController {

    private final JobLauncher jobLauncher;
    private final Job aggregateParticipationJob;

    @PostMapping("/aggregate")
    public ResponseEntity<Map<String, Object>> aggregate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) throws Exception {

        JobParameters params = new JobParametersBuilder()
                .addString("date", date.toString())
                .addLong("ts", System.currentTimeMillis()) // ensure uniqueness
                .toJobParameters();

        JobExecution exec = jobLauncher.run(aggregateParticipationJob, params);

        Map<String, Object> body = new HashMap<>();
        body.put("jobId", exec.getJobId());
        body.put("status", exec.getStatus().toString());
        body.put("exitStatus", exec.getExitStatus().getExitCode());
        body.put("date", date.toString());
        return ResponseEntity.ok(body);
    }
}

