package io.eventdriven.batchkafka.api.controller;

import io.eventdriven.batchkafka.domain.entity.CampaignStats;
import io.eventdriven.batchkafka.domain.repository.CampaignStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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

    /**
     * 특정 날짜의 전체 캠페인 통계 조회
     * GET /api/admin/stats/daily?date=2025-12-26
     */
    @GetMapping("/daily")
    public ResponseEntity<Map<String, Object>> getDailyStats(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        try {
            List<CampaignStats> stats = statsRepository.findByStatsDate(date);

            if (stats.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "date", date.toString(),
                        "message", "해당 날짜의 집계 데이터가 없습니다. 배치를 먼저 실행해주세요.",
                        "campaigns", List.of()
                ));
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

            Map<String, Object> response = new HashMap<>();
            response.put("date", date.toString());
            response.put("summary", Map.of(
                    "totalCampaigns", stats.size(),
                    "totalSuccess", totalSuccess,
                    "totalFail", totalFail,
                    "totalParticipation", totalCount,
                    "overallSuccessRate", totalCount > 0 ?
                            String.format("%.2f%%", (totalSuccess * 100.0 / totalCount)) : "0.00%"
            ));
            response.put("campaigns", campaigns);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("🚨 통계 조회 실패 - date: {}", date, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "통계 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 특정 캠페인의 일자별 통계 조회
     * GET /api/admin/stats/campaign/{campaignId}?startDate=2025-12-01&endDate=2025-12-31
     */
    @GetMapping("/campaign/{campaignId}")
    public ResponseEntity<Map<String, Object>> getCampaignStats(
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
                        .body(Map.of("error", "시작 날짜는 종료 날짜보다 이전이어야 합니다."));
            }

            List<CampaignStats> stats = statsRepository.findByCampaignIdAndStatsDateBetween(
                    campaignId, startDate, endDate);

            if (stats.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "campaignId", campaignId,
                        "startDate", startDate.toString(),
                        "endDate", endDate.toString(),
                        "message", "해당 기간의 통계 데이터가 없습니다.",
                        "dailyStats", List.of()
                ));
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

            Map<String, Object> response = new HashMap<>();
            response.put("campaignId", campaignId);
            response.put("campaignName", stats.get(0).getCampaign().getName());
            response.put("startDate", startDate.toString());
            response.put("endDate", endDate.toString());
            response.put("summary", Map.of(
                    "totalSuccess", totalSuccess,
                    "totalFail", totalFail,
                    "totalParticipation", totalCount,
                    "averageSuccessRate", totalCount > 0 ?
                            String.format("%.2f%%", (totalSuccess * 100.0 / totalCount)) : "0.00%"
            ));
            response.put("dailyStats", dailyStats);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("🚨 캠페인 통계 조회 실패 - campaignId: {}", campaignId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "통계 조회 중 오류가 발생했습니다."));
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
}
