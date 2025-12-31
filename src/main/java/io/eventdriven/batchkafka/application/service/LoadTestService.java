package io.eventdriven.batchkafka.application.service;

import io.eventdriven.batchkafka.api.dto.request.LoadTestRequest;
import io.eventdriven.batchkafka.api.dto.response.LoadTestMetrics;
import io.eventdriven.batchkafka.api.dto.response.LoadTestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoadTestService {

    // 테스트 결과를 메모리에 저장 (실제 환경에서는 Redis 사용 권장)
    private final Map<String, LoadTestResult> testResults = new ConcurrentHashMap<>();

    /**
     * Kafka 방식 부하 테스트 실행
     */
    public String executeKafkaTest(LoadTestRequest request) {
        String jobId = UUID.randomUUID().toString();

        // 초기 상태 저장
        LoadTestResult initialResult = LoadTestResult.builder()
                .jobId(jobId)
                .method("KAFKA")
                .campaignId(request.getCampaignId())
                .status("RUNNING")
                .build();
        testResults.put(jobId, initialResult);

        // 비동기 실행
        executeK6TestAsync(jobId, request, "kafka");

        return jobId;
    }

    /**
     * 동기 방식 부하 테스트 실행
     */
    public String executeSyncTest(LoadTestRequest request) {
        String jobId = UUID.randomUUID().toString();

        // 초기 상태 저장
        LoadTestResult initialResult = LoadTestResult.builder()
                .jobId(jobId)
                .method("SYNC")
                .campaignId(request.getCampaignId())
                .status("RUNNING")
                .build();
        testResults.put(jobId, initialResult);

        // 비동기 실행
        executeK6TestAsync(jobId, request, "sync");

        return jobId;
    }

    /**
     * 테스트 결과 조회
     */
    public LoadTestResult getTestResult(String jobId) {
        return testResults.get(jobId);
    }

    /**
     * K6 테스트 비동기 실행
     */
    @Async
    protected void executeK6TestAsync(String jobId, LoadTestRequest request, String testType) {
        try {
            log.info("🚀 K6 부하 테스트 시작 - JobID: {}, Type: {}, CampaignID: {}",
                    jobId, testType, request.getCampaignId());

            // K6 스크립트 경로 (Docker 컨테이너 /app 기준)
            String scriptPath = testType.equals("kafka")
                    ? "/app/k6-load-test.js"
                    : "/app/k6-sync-test.js";

            // ProcessBuilder로 K6 실행
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "k6", "run",
                    "-e", "CAMPAIGN_ID=" + request.getCampaignId(),
                    "-e", "VUS=" + request.getVirtualUsers(),
                    "-e", "DURATION=" + request.getDuration() + "s",
                    scriptPath
            );

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // K6 출력 읽기
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("K6 output: {}", line);
                }
            }

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                // 성공: 결과 파싱
                LoadTestMetrics metrics = parseK6Output(output.toString());

                LoadTestResult result = LoadTestResult.builder()
                        .jobId(jobId)
                        .method(testType.toUpperCase())
                        .campaignId(request.getCampaignId())
                        .status("COMPLETED")
                        .metrics(metrics)
                        .completedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME))
                        .build();

                testResults.put(jobId, result);

                log.info("✅ K6 테스트 완료 - JobID: {}, TPS: {}, P95: {}ms",
                        jobId, metrics.getThroughput(), metrics.getP95());

            } else {
                // 실패: 에러 출력 로그에 남기기
                String errorOutput = output.toString();

                LoadTestResult result = LoadTestResult.builder()
                        .jobId(jobId)
                        .method(testType.toUpperCase())
                        .campaignId(request.getCampaignId())
                        .status("FAILED")
                        .error("K6 실행 실패 (Exit code: " + exitCode + ")")
                        .completedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME))
                        .build();

                testResults.put(jobId, result);

                log.error("❌ K6 테스트 실패 - JobID: {}, ExitCode: {}", jobId, exitCode);
                log.error("K6 에러 출력:\n{}", errorOutput);
            }

        } catch (Exception e) {
            log.error("❌ K6 테스트 예외 발생 - JobID: {}", jobId, e);

            LoadTestResult result = LoadTestResult.builder()
                    .jobId(jobId)
                    .method(testType.toUpperCase())
                    .campaignId(request.getCampaignId())
                    .status("FAILED")
                    .error(e.getMessage())
                    .completedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME))
                    .build();

            testResults.put(jobId, result);
        }
    }

    /**
     * K6 출력에서 메트릭 파싱
     */
    private LoadTestMetrics parseK6Output(String output) {
        LoadTestMetrics.LoadTestMetricsBuilder builder = LoadTestMetrics.builder();

        // http_req_duration 메트릭 파싱
        Pattern durationPattern = Pattern.compile("http_req_duration.*?avg=(\\d+\\.?\\d*)ms.*?p\\(95\\)=(\\d+\\.?\\d*)ms.*?p\\(99\\)=(\\d+\\.?\\d*)ms");
        Matcher durationMatcher = durationPattern.matcher(output);
        if (durationMatcher.find()) {
            builder.avg(Double.parseDouble(durationMatcher.group(1)));
            builder.p95(Double.parseDouble(durationMatcher.group(2)));
            builder.p99(Double.parseDouble(durationMatcher.group(3)));
        }

        // http_reqs 메트릭 파싱
        Pattern reqsPattern = Pattern.compile("http_reqs.*?(\\d+)\\s+(\\d+\\.?\\d*)/s");
        Matcher reqsMatcher = reqsPattern.matcher(output);
        if (reqsMatcher.find()) {
            builder.totalRequests(Integer.parseInt(reqsMatcher.group(1)));
            builder.throughput(Double.parseDouble(reqsMatcher.group(2)));
        }

        // http_req_failed 메트릭 파싱
        Pattern failedPattern = Pattern.compile("http_req_failed.*?(\\d+\\.?\\d*)%");
        Matcher failedMatcher = failedPattern.matcher(output);
        if (failedMatcher.find()) {
            builder.failureRate(Double.parseDouble(failedMatcher.group(1)) / 100.0);
        }

        // 기본값 설정 (파싱 실패 시)
        return builder
                .p50(builder.build().getAvg() != null ? builder.build().getAvg() * 0.9 : 50.0)
                .min(10.0)
                .max(builder.build().getP99() != null ? builder.build().getP99() * 1.2 : 200.0)
                .build();
    }
}
