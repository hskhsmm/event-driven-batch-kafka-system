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

    private final KafkaTopicService kafkaTopicService;

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

        // 비동기 실행 (CompletableFuture 사용하여 self-invocation 문제 해결)
        java.util.concurrent.CompletableFuture.runAsync(() ->
            executeK6TestAsync(jobId, request, "kafka")
        );

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

        // 비동기 실행 (CompletableFuture 사용하여 self-invocation 문제 해결)
        java.util.concurrent.CompletableFuture.runAsync(() ->
            executeK6TestAsync(jobId, request, "sync")
        );

        return jobId;
    }

    /**
     * 테스트 결과 조회
     */
    public LoadTestResult getTestResult(String jobId) {
        return testResults.get(jobId);
    }

    /**
     * K6 테스트 실행 (CompletableFuture로 비동기 실행됨)
     */
    protected void executeK6TestAsync(String jobId, LoadTestRequest request, String testType) {
        try {
            log.info("🚀 K6 부하 테스트 시작 - JobID: {}, Type: {}, CampaignID: {}, TotalRequests: {}, Partitions: {}",
                    jobId, testType, request.getCampaignId(), request.getTotalRequests(), request.getPartitions());

            // Kafka 테스트인 경우 파티션 정보 로깅
            if (testType.equals("kafka")) {
                log.info("ℹ️ Kafka 파티션 수동 관리 모드");
                log.info("📌 요청된 파티션 수: {} (실제 파티션은 Docker로 수동 설정)", request.getPartitions());
                log.info("💡 파티션 변경 명령어: docker exec kafka kafka-topics --bootstrap-server kafka:29092 --alter --topic campaign-participation-topic --partitions {}", request.getPartitions());

                // 파티션 자동 조정 제거 (kafka-clients 4.1.1 AdminClient 버그로 인해)
                // 대신 Docker 명령어로 수동 관리:
                // docker exec kafka kafka-topics --bootstrap-server kafka:29092 --alter --topic campaign-participation-topic --partitions <원하는 파티션 수>
            }

            // 총 요청 수 기반으로 rate와 duration 계산
            K6Config config = calculateK6Config(request.getTotalRequests(), testType);

            log.info("📊 K6 설정 - Rate: {}/s, Duration: {}s, MaxVUs: {}",
                    config.rate, config.duration, config.maxVUs);

            // K6 스크립트 경로 (Docker 컨테이너 /app 기준)
            String scriptPath = testType.equals("kafka")
                    ? "/app/k6-load-test.js"
                    : "/app/k6-sync-test.js";

            // ProcessBuilder로 K6 실행
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "k6", "run",
                    "-e", "CAMPAIGN_ID=" + request.getCampaignId(),
                    "-e", "TOTAL_REQUESTS=" + request.getTotalRequests(),
                    "-e", "RATE=" + config.rate,
                    "-e", "DURATION=" + config.duration,
                    "-e", "MAX_VUS=" + config.maxVUs,
                    "-e", "PARTITIONS=" + request.getPartitions(),
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

            // 타임아웃 5분 설정 (K6 테스트가 무한 대기하는 것 방지)
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("K6 테스트 타임아웃 (5분 초과)");
            }

            int exitCode = process.exitValue();

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

        // http_req_duration 라인 찾기
        Pattern linePattern = Pattern.compile("http_req_duration.*");
        Matcher lineMatcher = linePattern.matcher(output);

        if (lineMatcher.find()) {
            String durationLine = lineMatcher.group();

            // avg 파싱
            Pattern avgPattern = Pattern.compile("avg=(\\d+\\.?\\d*)(ms|s|µs|m)");
            Matcher avgMatcher = avgPattern.matcher(durationLine);
            if (avgMatcher.find()) {
                builder.avg(convertToMs(avgMatcher.group(1), avgMatcher.group(2)));
            }

            // p(95) 파싱
            Pattern p95Pattern = Pattern.compile("p\\(95\\)=(\\d+\\.?\\d*)(ms|s|µs|m)");
            Matcher p95Matcher = p95Pattern.matcher(durationLine);
            if (p95Matcher.find()) {
                builder.p95(convertToMs(p95Matcher.group(1), p95Matcher.group(2)));
            }

            // p(99) 파싱 (optional)
            Pattern p99Pattern = Pattern.compile("p\\(99\\)=(\\d+\\.?\\d*)(ms|s|µs|m)");
            Matcher p99Matcher = p99Pattern.matcher(durationLine);
            boolean hasP99 = p99Matcher.find();  // 결과를 변수에 저장
            if (hasP99) {
                builder.p99(convertToMs(p99Matcher.group(1), p99Matcher.group(2)));
            }

            // max 파싱 (p99가 없으면 max 사용)
            Pattern maxPattern = Pattern.compile("max=(\\d+\\.?\\d*)(ms|s|µs|m)");
            Matcher maxMatcher = maxPattern.matcher(durationLine);
            if (maxMatcher.find()) {
                double maxValue = convertToMs(maxMatcher.group(1), maxMatcher.group(2));
                builder.max(maxValue);
                // p99가 없으면 max를 p99로 사용
                if (!hasP99) {  // 저장된 변수 사용
                    builder.p99(maxValue);
                }
            }

            // min 파싱
            Pattern minPattern = Pattern.compile("min=(\\d+\\.?\\d*)(ms|s|µs|m)");
            Matcher minMatcher = minPattern.matcher(durationLine);
            if (minMatcher.find()) {
                builder.min(convertToMs(minMatcher.group(1), minMatcher.group(2)));
            }
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

        // p50 계산 (avg의 90%로 추정)
        LoadTestMetrics temp = builder.build();
        if (temp.getP50() == null && temp.getAvg() != null) {
            builder.p50(temp.getAvg() * 0.9);
        }

        return builder.build();
    }

    /**
     * K6 시간 단위를 밀리초로 변환
     */
    private double convertToMs(String value, String unit) {
        double numValue = Double.parseDouble(value);
        switch (unit) {
            case "s":
                return numValue * 1000; // 초 → 밀리초
            case "m":
                return numValue * 60 * 1000; // 분 → 밀리초
            case "µs":
                return numValue / 1000; // 마이크로초 → 밀리초
            case "ms":
            default:
                return numValue; // 이미 밀리초
        }
    }

    /**
     * 총 요청 수 기반으로 K6 설정 계산
     *
     * @param totalRequests 총 요청 수
     * @param testType "kafka" 또는 "sync"
     * @return K6 설정 (rate, duration, maxVUs)
     */
    private K6Config calculateK6Config(int totalRequests, String testType) {
        if (testType.equals("kafka")) {
            // Kafka: 응답이 빠름 (~15ms) → 짧은 시간에 많은 요청
            // 현실적인 설정: duration을 동적으로 조정
            int duration;
            int maxVUs;

            if (totalRequests <= 5000) {
                duration = 10; // 소량: 10초 (백프레셔 고려)
                maxVUs = 5000;
            } else if (totalRequests <= 15000) {
                duration = 30; // 중량: 30초 (백프레셔 200ms 고려)
                maxVUs = 8000;
            } else if (totalRequests <= 50000) {
                duration = 60; // 대량: 60초
                maxVUs = 10000;
            } else if (totalRequests <= 100000) {
                duration = 100; // 10만: 100초 (백프레셔 1,500건마다 200ms 반영)
                maxVUs = 15000;
            } else if (totalRequests <= 500000) {
                duration = 1200; // 50만: 1200초 (20분) - 백프레셔 5,000건마다
                maxVUs = 20000;
            } else if (totalRequests <= 1000000) {
                duration = 2400; // 100만: 2400초 (40분) - 백프레셔 10,000건마다
                maxVUs = 25000;
            } else if (totalRequests <= 3000000) {
                duration = 7200; // 300만: 7200초 (2시간) - 백프레셔 10,000건마다
                maxVUs = 30000;
            } else {
                // 300만 초과: 3시간 (최대 안정성)
                duration = 10800; // 3시간
                maxVUs = 30000;
            }

            int rate = totalRequests / duration; // 초당 요청 수

            return new K6Config(rate, duration, maxVUs);
        } else {
            // Sync: 응답이 느림 (~4.5s) → 긴 시간에 걸쳐 요청
            int duration = 30; // 30초
            int rate = totalRequests / duration; // 초당 요청 수
            int maxVUs = Math.max(rate * 10, 5000); // rate의 10배 또는 최소 5000

            return new K6Config(rate, duration, maxVUs);
        }
    }

    /**
     * K6 설정을 담는 내부 클래스
     */
    private static class K6Config {
        final int rate;       // 초당 요청 수
        final int duration;   // 테스트 지속 시간 (초)
        final int maxVUs;     // 최대 가상 사용자 수

        K6Config(int rate, int duration, int maxVUs) {
            this.rate = rate;
            this.duration = duration;
            this.maxVUs = maxVUs;
        }
    }
}
