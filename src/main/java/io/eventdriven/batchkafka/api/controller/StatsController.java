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
     * 순서 분석 API - Kafka offset 순서 vs 실제 처리 순서 비교
     * GET /api/admin/stats/order-analysis/{campaignId}
     */
    @GetMapping("/order-analysis/{campaignId}")
    public ResponseEntity<ApiResponse<?>> analyzeProcessingOrder(@PathVariable Long campaignId) {
        try {
            long startTime = System.currentTimeMillis();

            // 1. Kafka offset 순서로 데이터 조회
            List<ParticipationHistory> records = participationHistoryRepository
                    .findByCampaignIdOrderByKafkaOffsetAsc(campaignId);

            if (records.isEmpty()) {
                Map<String, Object> emptyData = Map.of(
                        "campaignId", campaignId,
                        "message", "Kafka 메타데이터가 없는 참여 이력입니다. 최근 테스트 데이터를 확인하세요."
                );
                return ResponseEntity.ok(ApiResponse.success("데이터 없음", emptyData));
            }

            // 2. 순서 일치율 계산 (offset 순서 vs created_at 순서)
            int orderMismatches = 0;
            for (int i = 0; i < records.size() - 1; i++) {
                ParticipationHistory current = records.get(i);
                ParticipationHistory next = records.get(i + 1);

                // offset은 오름차순인데, created_at이 역전되면 순서 섞임
                if (current.getCreatedAt().isAfter(next.getCreatedAt())) {
                    orderMismatches++;
                }
            }

            double orderAccuracy = records.size() > 1
                ? 100.0 * (records.size() - 1 - orderMismatches) / (records.size() - 1)
                : 100.0;

            // 3. 파티션별 분포
            Map<Integer, Long> partitionDistribution = records.stream()
                    .filter(r -> r.getKafkaPartition() != null)
                    .collect(Collectors.groupingBy(
                            ParticipationHistory::getKafkaPartition,
                            Collectors.counting()
                    ));

            // 4. 샘플 데이터 (처음 50개)
            List<Map<String, Object>> samples = records.stream()
                    .limit(50)
                    .map(r -> {
                        Map<String, Object> sample = new HashMap<>();
                        sample.put("offset", r.getKafkaOffset());
                        sample.put("partition", r.getKafkaPartition());
                        sample.put("userId", r.getUserId());
                        sample.put("status", r.getStatus().toString());
                        sample.put("processedAt", r.getCreatedAt().toString());
                        sample.put("kafkaTimestamp", r.getKafkaTimestamp() != null
                                ? Instant.ofEpochMilli(r.getKafkaTimestamp())
                                        .atZone(ZoneId.of("Asia/Seoul"))
                                        .toLocalDateTime()
                                        .toString()
                                : null);
                        return sample;
                    })
                    .collect(Collectors.toList());

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            // 5. 응답 데이터 구성
            Map<String, Object> data = new HashMap<>();
            data.put("campaignId", campaignId);
            data.put("queryTimeMs", duration);
            data.put("summary", Map.of(
                    "totalRecords", records.size(),
                    "orderMismatches", orderMismatches,
                    "orderAccuracy", String.format("%.2f%%", orderAccuracy),
                    "partitionCount", partitionDistribution.size()
            ));
            data.put("partitionDistribution", partitionDistribution);
            data.put("samples", samples);

            log.info("📊 순서 분석 완료 - campaignId: {}, totalRecords: {}, orderAccuracy: {:.2f}%, queryTime: {}ms",
                    campaignId, records.size(), orderAccuracy, duration);

            return ResponseEntity.ok(ApiResponse.success(data));

        } catch (Exception e) {
            log.error("🚨 순서 분석 실패 - campaignId: {}", campaignId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.fail("순서 분석 중 오류가 발생했습니다."));
        }
    }
}
