package io.eventdriven.batchkafka.api.controller;

import io.eventdriven.batchkafka.api.common.ApiResponse;
import io.eventdriven.batchkafka.domain.entity.CampaignStats;
import io.eventdriven.batchkafka.domain.entity.ParticipationHistory;
import io.eventdriven.batchkafka.domain.repository.CampaignStatsRepository;
import io.eventdriven.batchkafka.domain.repository.ParticipationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 캠페인 통계 조회 API
 * - 일자별 집계 데이터 조회
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/stats")
@RequiredArgsConstructor
public class StatsController {

    private final CampaignStatsRepository statsRepository;
    private final ParticipationHistoryRepository participationHistoryRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 원본 데이터 직접 집계 (배치 없이 - 느린 API, 성능 비교용)
     * GET /api/admin/stats/raw?date=2025-12-26
     */
    @GetMapping("/raw")
    public ResponseEntity<ApiResponse<?>> getRawStats(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        try {
            long startTime = System.currentTimeMillis();

            // 배치 없이 원본 테이블에서 직접 집계 (느림!)
            String sql = """
                SELECT
                    c.id as campaign_id,
                    c.name as campaign_name,
                    COALESCE(SUM(CASE WHEN p.status = 'SUCCESS' THEN 1 ELSE 0 END), 0) as success_count,
                    COALESCE(SUM(CASE WHEN p.status = 'FAIL' THEN 1 ELSE 0 END), 0) as fail_count,
                    COALESCE(COUNT(p.id), 0) as total_count
                FROM campaign c
                LEFT JOIN participation_history p ON c.id = p.campaign_id AND DATE(p.created_at) = ?
                GROUP BY c.id, c.name
                HAVING total_count > 0
            """;

            List<Map<String, Object>> campaigns = jdbcTemplate.query(sql,
                (rs, rowNum) -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("campaignId", rs.getLong("campaign_id"));
                    item.put("campaignName", rs.getString("campaign_name"));
                    item.put("successCount", rs.getLong("success_count"));
                    item.put("failCount", rs.getLong("fail_count"));
                    item.put("totalCount", rs.getLong("total_count"));
                    long success = rs.getLong("success_count");
                    long total = rs.getLong("total_count");
                    item.put("successRate", total > 0 ?
                        String.format("%.2f%%", (success * 100.0 / total)) : "0.00%");
                    return item;
                },
                java.sql.Date.valueOf(date)
            );

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            // 전체 집계
            long totalSuccess = campaigns.stream()
                .mapToLong(c -> ((Number) c.get("successCount")).longValue()).sum();
            long totalFail = campaigns.stream()
                .mapToLong(c -> ((Number) c.get("failCount")).longValue()).sum();
            long totalCount = totalSuccess + totalFail;

            Map<String, Object> data = new HashMap<>();
            data.put("date", date.toString());
            data.put("method", "RAW_QUERY");
            data.put("queryTimeMs", duration);  // 쿼리 실행 시간
            data.put("summary", Map.of(
                    "totalCampaigns", campaigns.size(),
                    "totalSuccess", totalSuccess,
                    "totalFail", totalFail,
                    "totalParticipation", totalCount,
                    "overallSuccessRate", totalCount > 0 ?
                            String.format("%.2f%%", (totalSuccess * 100.0 / totalCount)) : "0.00%"
            ));
            data.put("campaigns", campaigns);

            log.info("📊 원본 집계 완료 - date: {}, queryTime: {}ms", date, duration);

            return ResponseEntity.ok(ApiResponse.success(data));

        } catch (Exception e) {
            log.error("🚨 원본 통계 조회 실패 - date: {}", date, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.fail("통계 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 특정 날짜의 전체 캠페인 통계 조회 (배치 집계 후 - 빠른 API)
     * GET /api/admin/stats/daily?date=2025-12-26
     */
    @GetMapping("/daily")
    public ResponseEntity<ApiResponse<?>> getDailyStats(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        try {
            long startTime = System.currentTimeMillis();

            List<CampaignStats> stats = statsRepository.findByStatsDate(date);

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            if (stats.isEmpty()) {
                Map<String, Object> emptyData = new HashMap<>();
                emptyData.put("date", date.toString());
                emptyData.put("method", "BATCH_AGGREGATED");
                emptyData.put("queryTimeMs", duration);
                emptyData.put("campaigns", List.of());
                return ResponseEntity.ok(
                        ApiResponse.success("해당 날짜의 집계 데이터가 없습니다. 배치를 먼저 실행해주세요.", emptyData)
                );
            }

            // 통계 데이터를 DTO로 변환
            List<Map<String, Object>> campaigns = stats.stream()
                    .map(stat -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("campaignId", stat.getCampaign().getId());
                        item.put("campaignName", stat.getCampaign().getName());
                        item.put("successCount", stat.getSuccessCount());
                        item.put("failCount", stat.getFailCount());
                        item.put("totalCount", stat.getSuccessCount() + stat.getFailCount());
                        item.put("successRate", calculateSuccessRate(stat));
                        item.put("statsDate", stat.getStatsDate());
                        return item;
                    })
                    .collect(Collectors.toList());

            // 전체 집계
            long totalSuccess = stats.stream().mapToLong(CampaignStats::getSuccessCount).sum();
            long totalFail = stats.stream().mapToLong(CampaignStats::getFailCount).sum();
            long totalCount = totalSuccess + totalFail;

            Map<String, Object> data = new HashMap<>();
            data.put("date", date.toString());
            data.put("method", "BATCH_AGGREGATED");
            data.put("queryTimeMs", duration);  // 쿼리 실행 시간
            data.put("summary", Map.of(
                    "totalCampaigns", stats.size(),
                    "totalSuccess", totalSuccess,
                    "totalFail", totalFail,
                    "totalParticipation", totalCount,
                    "overallSuccessRate", totalCount > 0 ?
                            String.format("%.2f%%", (totalSuccess * 100.0 / totalCount)) : "0.00%"
            ));
            data.put("campaigns", campaigns);

            log.info("📊 배치 집계 조회 완료 - date: {}, queryTime: {}ms", date, duration);

            return ResponseEntity.ok(ApiResponse.success(data));

        } catch (Exception e) {
            log.error("🚨 통계 조회 실패 - date: {}", date, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.fail("통계 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 특정 캠페인의 일자별 통계 조회
     * GET /api/admin/stats/campaign/{campaignId}?startDate=2025-12-01&endDate=2025-12-31
     */
    @GetMapping("/campaign/{campaignId}")
    public ResponseEntity<ApiResponse<?>> getCampaignStats(
            @PathVariable Long campaignId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        try {
            // 기본값: 최근 7일
            if (startDate == null) {
                startDate = LocalDate.now().minusDays(7);
            }
            if (endDate == null) {
                endDate = LocalDate.now();
            }

            // 날짜 범위 검증
            if (startDate.isAfter(endDate)) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.fail("시작 날짜는 종료 날짜보다 이전이어야 합니다."));
            }

            List<CampaignStats> stats = statsRepository.findByCampaignIdAndStatsDateBetween(
                    campaignId, startDate, endDate);

            if (stats.isEmpty()) {
                Map<String, Object> emptyData = Map.of(
                        "campaignId", campaignId,
                        "startDate", startDate.toString(),
                        "endDate", endDate.toString(),
                        "dailyStats", List.of()
                );
                return ResponseEntity.ok(
                        ApiResponse.success("해당 기간의 통계 데이터가 없습니다.", emptyData)
                );
            }

            // 일자별 통계
            List<Map<String, Object>> dailyStats = stats.stream()
                    .<Map<String, Object>>map(stat -> Map.of(
                            "date", stat.getStatsDate().toString(),
                            "successCount", stat.getSuccessCount(),
                            "failCount", stat.getFailCount(),
                            "totalCount", stat.getSuccessCount() + stat.getFailCount(),
                            "successRate", calculateSuccessRate(stat)
                    ))
                    .collect(Collectors.toList());

            // 기간 집계
            long totalSuccess = stats.stream().mapToLong(CampaignStats::getSuccessCount).sum();
            long totalFail = stats.stream().mapToLong(CampaignStats::getFailCount).sum();
            long totalCount = totalSuccess + totalFail;

            Map<String, Object> data = new HashMap<>();
            data.put("campaignId", campaignId);
            data.put("campaignName", stats.get(0).getCampaign().getName());
            data.put("startDate", startDate.toString());
            data.put("endDate", endDate.toString());
            data.put("summary", Map.of(
                    "totalSuccess", totalSuccess,
                    "totalFail", totalFail,
                    "totalParticipation", totalCount,
                    "averageSuccessRate", totalCount > 0 ?
                            String.format("%.2f%%", (totalSuccess * 100.0 / totalCount)) : "0.00%"
            ));
            data.put("dailyStats", dailyStats);

            return ResponseEntity.ok(ApiResponse.success(data));

        } catch (Exception e) {
            log.error("🚨 캠페인 통계 조회 실패 - campaignId: {}", campaignId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.fail("통계 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 성공률 계산
     */
    private String calculateSuccessRate(CampaignStats stat) {
        long total = stat.getSuccessCount() + stat.getFailCount();
        if (total == 0) {
            return "0.00%";
        }
        double rate = (stat.getSuccessCount() * 100.0) / total;
        return String.format("%.2f%%", rate);
    }

    /**
     * 순서 위반 케이스만 추출 (검증용)
     * GET /api/admin/stats/order-violations/{campaignId}?limit=100
     */
    @GetMapping("/order-violations/{campaignId}")
    public ResponseEntity<ApiResponse<?>> getOrderViolations(
            @PathVariable Long campaignId,
            @RequestParam(defaultValue = "100") int limit) {
        try {
            long startTime = System.currentTimeMillis();

            // 1. 모든 레코드 조회
            List<ParticipationHistory> allRecords = participationHistoryRepository
                    .findByCampaignIdOrderByKafkaTimestampAsc(campaignId);

            if (allRecords.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success("데이터 없음", Map.of()));
            }

            // 2. Kafka offset 순서로 정렬
            List<ParticipationHistory> arrivalOrder = allRecords.stream()
                    .filter(r -> r.getProcessingSequence() != null
                              && r.getKafkaPartition() != null
                              && r.getKafkaOffset() != null)
                    .sorted(java.util.Comparator.comparing(ParticipationHistory::getKafkaPartition)
                            .thenComparing(ParticipationHistory::getKafkaOffset))
                    .collect(Collectors.toList());

        log.info("### DEBUG: Top 5 arrivalOrder in getOrderViolations ###");
        for(int i=0; i < Math.min(5, arrivalOrder.size()); i++) {
            ParticipationHistory r = arrivalOrder.get(i);
            log.info("Item " + i + ": P=" + r.getKafkaPartition() + ", O=" + r.getKafkaOffset() + ", Seq=" + r.getProcessingSequence());
        }
        log.info("### END DEBUG ###");

            // 3. 순서 위반 케이스 추출
            List<Map<String, Object>> violations = new java.util.ArrayList<>();

            // 전체 구간 스캔: 위반은 최대 limit개까지만 수집
            for (int i = 0; i < (arrivalOrder.size() - 1); i++) {
                if (violations.size() >= limit) break;

                ParticipationHistory current = arrivalOrder.get(i);
                ParticipationHistory next = arrivalOrder.get(i + 1);

                if (current.getProcessingSequence() > next.getProcessingSequence()) {
                    Map<String, Object> violation = new HashMap<>();
                    violation.put("index", i);
                    violation.put("current", Map.of(
                            "userId", current.getUserId(),
                            "timestamp", current.getKafkaTimestamp(),
                            "partition", current.getKafkaPartition(),
                            "offset", current.getKafkaOffset(),
                            "processingSeq", current.getProcessingSequence(),
                            "status", current.getStatus().toString()
                    ));
                    violation.put("next", Map.of(
                            "userId", next.getUserId(),
                            "timestamp", next.getKafkaTimestamp(),
                            "partition", next.getKafkaPartition(),
                            "offset", next.getKafkaOffset(),
                            "processingSeq", next.getProcessingSequence(),
                            "status", next.getStatus().toString()
                    ));
                    violation.put("explanation", String.format(
                            "offset P%d:%d(seq=%d)가 P%d:%d(seq=%d)보다 늦게 처리됨",
                            current.getKafkaPartition(), current.getKafkaOffset(), current.getProcessingSequence(),
                            next.getKafkaPartition(), next.getKafkaOffset(), next.getProcessingSequence()
                    ));
                    violations.add(violation);
                }
            }

            long endTime = System.currentTimeMillis();

            Map<String, Object> data = new HashMap<>();
            data.put("campaignId", campaignId);
            data.put("totalRecords", arrivalOrder.size());
            data.put("violationsFound", violations.size());
            data.put("violations", violations);
            data.put("queryTimeMs", endTime - startTime);

            return ResponseEntity.ok(ApiResponse.success(data));

        } catch (Exception e) {
            log.error("🚨 순서 위반 조회 실패 - campaignId: {}", campaignId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.fail("순서 위반 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 순서 분석 API - Kafka offset 순서 vs 실제 처리 순서 비교
     * GET /api/admin/stats/order-analysis/{campaignId}
     *
     * === 측정 목적 ===
     * 파티션 수 증가에 따른 "Kafka offset 순서 vs 처리 순서" 일치도 측정
     * → 처리량 증가 vs 순서 보장 트레이드오프 분석
     *
     * === Kafka offset 순서 정의 ===
     * Kafka는 파티션 내에서 offset 순서를 보장:
     * - 1차: kafkaPartition (파티션 번호)
     * - 2차: kafkaOffset (파티션 내 순서 번호, 0부터 시작)
     *
     * 파티션 1개: offset 0, 1, 2, 3, ... (완벽한 순서)
     * 파티션 3개:
     *   - P0: 0, 1, 2, ... (파티션 내 순서)
     *   - P1: 0, 1, 2, ... (파티션 내 순서)
     *   - P2: 0, 1, 2, ... (파티션 내 순서)
     *   - 파티션 간 순서는 Consumer 스레드 경쟁으로 결정됨
     *
     * === 처리 순서 ===
     * processingSequence: Consumer가 메시지 처리를 시작한 전역 순서 번호 (AtomicLong)
     * - 모든 파티션 통틀어서 1, 2, 3, 4, ... 순차 증가
     *
     * === 측정 방법 ===
     * 1. 모든 레코드를 offset 순서로 정렬 (partition → offset)
     * 2. 인접한 레코드 쌍 비교:
     *    - offset 순서상 앞선 메시지가 처리 순서상 뒤면 → 순서 불일치
     * 3. 순서 정확도 = (일치 쌍 / 전체 쌍) × 100%
     *
     * === 결과 해석 ===
     * 파티션 1개: 100% (offset 순서 = 처리 순서)
     * 파티션 3개: ~33% (각 파티션은 순서 유지하지만, 파티션 간 뒤섞임)
     * 파티션 12개: ~8% (파티션 간 심하게 뒤섞임)
     *
     * === 비즈니스 의미 ===
     * - 파티션 1개: "선착순"이 완벽하게 보장됨
     * - 파티션 3개: 파티션별로 "선착순"이지만, 전체적으로는 섞임
     * - 파티션 12개: "선착순" 의미 없음, 재고만 정확
     */
    @GetMapping("/order-analysis/{campaignId}")
    public ResponseEntity<ApiResponse<?>> analyzeProcessingOrder(@PathVariable Long campaignId) {
        try {
            long startTime = System.currentTimeMillis();

            // 1. Kafka 메타데이터가 있는 모든 참여 이력 조회
            List<ParticipationHistory> allRecords = participationHistoryRepository
                    .findByCampaignIdOrderByKafkaTimestampAsc(campaignId);

            if (allRecords.isEmpty()) {
                Map<String, Object> emptyData = Map.of(
                        "campaignId", campaignId,
                        "message", "Kafka 메타데이터가 없는 참여 이력입니다. 최근 테스트 데이터를 확인하세요."
                );
                return ResponseEntity.ok(ApiResponse.success("데이터 없음", emptyData));
            }

            // 2. 파티션별로 데이터 그룹화 (파티션별 순서 분석용)
            Map<Integer, List<ParticipationHistory>> partitionGroups = allRecords.stream()
                    .filter(r -> r.getKafkaPartition() != null && r.getKafkaOffset() != null)
                    .collect(Collectors.groupingBy(ParticipationHistory::getKafkaPartition));

            // 3-1. 파티션별 순서 불일치 계산 (참고용)
            Map<Integer, Integer> partitionMismatches = new HashMap<>();
            for (Map.Entry<Integer, List<ParticipationHistory>> entry : partitionGroups.entrySet()) {
                Integer partition = entry.getKey();
                List<ParticipationHistory> partitionRecords = entry.getValue();
                partitionRecords.sort(java.util.Comparator.comparing(ParticipationHistory::getKafkaOffset));

                int partitionMismatch = 0;
                for (int i = 0; i < partitionRecords.size() - 1; i++) {
                    ParticipationHistory current = partitionRecords.get(i);
                    ParticipationHistory next = partitionRecords.get(i + 1);

                    boolean orderMismatch;
                    if (current.getProcessingStartedAtNanos() != null && next.getProcessingStartedAtNanos() != null) {
                        orderMismatch = current.getProcessingStartedAtNanos() > next.getProcessingStartedAtNanos();
                    } else {
                        orderMismatch = current.getCreatedAt().isAfter(next.getCreatedAt());
                    }

                    if (orderMismatch) {
                        partitionMismatch++;
                    }
                }
                partitionMismatches.put(partition, partitionMismatch);
            }

            // 3-2. 전역 순서 불일치 계산 (Kafka offset 순서 vs 처리 순서)

            // Kafka 순서 정의: partition → offset
            // (Kafka는 파티션 내 offset 순서를 보장하므로, 이것이 진짜 도착 순서)
            List<ParticipationHistory> arrivalOrder = allRecords.stream()
                    .filter(r -> r.getProcessingSequence() != null
                              && r.getKafkaPartition() != null
                              && r.getKafkaOffset() != null)
                    .sorted(java.util.Comparator.comparing(ParticipationHistory::getKafkaPartition)
                            .thenComparing(ParticipationHistory::getKafkaOffset))
                    .collect(Collectors.toList());

            // 메인 지표: 전체 도착 순서 기준
            int totalOrderMismatches = 0;
            int totalComparisons = arrivalOrder.size() - 1;

            for (int i = 0; i < arrivalOrder.size() - 1; i++) {
                ParticipationHistory current = arrivalOrder.get(i);
                ParticipationHistory next = arrivalOrder.get(i + 1);

                // 도착 순서상 앞선 메시지가 나중에 처리되었으면 순서 위반
                if (current.getProcessingSequence() > next.getProcessingSequence()) {
                    totalOrderMismatches++;
                }
            }

            double orderAccuracy = totalComparisons > 0
                    ? 100.0 * (totalComparisons - totalOrderMismatches) / totalComparisons
                    : 100.0;

            // 4. 파티션별 메시지 분포
            Map<Integer, Long> partitionDistribution = partitionGroups.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> (long) e.getValue().size()
                    ));

            // 5. 샘플 데이터 (각 파티션에서 처음 10개씩, 순서 위반 케이스 포함)
            List<Map<String, Object>> samples = new java.util.ArrayList<>();
            for (Map.Entry<Integer, List<ParticipationHistory>> entry : partitionGroups.entrySet()) {
                Integer partition = entry.getKey();
                List<ParticipationHistory> partitionRecords = entry.getValue();

                // offset 순서로 정렬
                partitionRecords.sort(java.util.Comparator.comparing(ParticipationHistory::getKafkaOffset));

                // 각 파티션에서 처음 10개만
                for (int i = 0; i < Math.min(10, partitionRecords.size()); i++) {
                    ParticipationHistory r = partitionRecords.get(i);
                    Map<String, Object> sample = new HashMap<>();
                    sample.put("partition", r.getKafkaPartition());
                    sample.put("offset", r.getKafkaOffset());
                    sample.put("userId", r.getUserId());
                    sample.put("status", r.getStatus().toString());
                    sample.put("processingSequence", r.getProcessingSequence()); // 처리 순서 번호
                    sample.put("kafkaTimestamp", r.getKafkaTimestamp() != null
                            ? Instant.ofEpochMilli(r.getKafkaTimestamp())
                                    .atZone(ZoneId.of("Asia/Seoul"))
                                    .toLocalDateTime()
                                    .toString()
                            : null);
                    sample.put("processedAt", r.getCreatedAt().toString());

                    // 다음 레코드와 비교하여 순서 위반 여부 표시
                    if (i < partitionRecords.size() - 1) {
                        ParticipationHistory next = partitionRecords.get(i + 1);
                        // processingSequence가 있으면 그걸로 비교, 없으면 createdAt으로 비교
                        boolean orderViolation = false;
                        if (r.getProcessingSequence() != null && next.getProcessingSequence() != null) {
                            orderViolation = r.getProcessingSequence() > next.getProcessingSequence();
                        } else {
                            orderViolation = r.getCreatedAt().isAfter(next.getCreatedAt());
                        }
                        sample.put("orderViolation", orderViolation);
                    } else {
                        sample.put("orderViolation", false);
                    }

                    samples.add(sample);
                }
            }

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            // 6. 응답 데이터 구성
            Map<String, Object> data = new HashMap<>();
            data.put("campaignId", campaignId);
            data.put("queryTimeMs", duration);

            // 메인 지표
            data.put("summary", Map.of(
                    "totalRecords", allRecords.size(),
                    "orderMismatches", totalOrderMismatches,
                    "orderAccuracy", String.format("%.2f%%", orderAccuracy),
                    "partitionCount", partitionGroups.size(),
                    "totalComparisons", totalComparisons
            ));

            data.put("partitionDistribution", partitionDistribution);
            data.put("partitionMismatches", partitionMismatches);
            data.put("samples", samples);

            // 해석 가이드
            data.put("interpretation", Map.of(
                    "orderAccuracy", "Kafka offset 순서 기준 (partition → offset)",
                    "guide", orderAccuracy >= 99.0 ? "완벽한 순서 보장 (파티션 내)" :
                             orderAccuracy >= 95.0 ? "높은 순서 보장" :
                             orderAccuracy >= 85.0 ? "중간 순서 보장" :
                             "낮은 순서 보장 (파티션 많음)",
                    "note", partitionGroups.size() == 1
                            ? "파티션 1개: offset 순서 = 처리 순서 (100% 기대)"
                            : "파티션 " + partitionGroups.size() + "개: 파티션 내에서만 순서 보장, 파티션 간은 경쟁"
            ));

            log.info("📊 순서 분석 완료 - campaignId: {}, totalRecords: {}, partitions: {}, orderAccuracy: {:.2f}%, queryTime: {}ms",
                    campaignId, allRecords.size(), partitionGroups.size(), orderAccuracy, duration);

            return ResponseEntity.ok(ApiResponse.success(data));

        } catch (Exception e) {
            log.error("🚨 순서 분석 실패 - campaignId: {}", campaignId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.fail("순서 분석 중 오류가 발생했습니다."));
        }
    }
}
