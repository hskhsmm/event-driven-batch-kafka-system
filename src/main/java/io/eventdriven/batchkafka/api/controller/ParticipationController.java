package io.eventdriven.batchkafka.api.controller;

import io.eventdriven.batchkafka.api.common.ApiResponse;
import io.eventdriven.batchkafka.api.dto.request.ParticipationRequest;
import io.eventdriven.batchkafka.api.exception.business.CampaignNotFoundException;
import io.eventdriven.batchkafka.application.service.ParticipationService;
import io.eventdriven.batchkafka.application.service.RedisStockService;
import io.eventdriven.batchkafka.domain.entity.Campaign;
import io.eventdriven.batchkafka.domain.entity.ParticipationHistory;
import io.eventdriven.batchkafka.domain.repository.CampaignRepository;
import io.eventdriven.batchkafka.domain.repository.ParticipationHistoryRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
public class ParticipationController {

    private final ParticipationService participationService;
    private final CampaignRepository campaignRepository;
    private final ParticipationHistoryRepository participationHistoryRepository;
    private final RedisStockService redisStockService;

    /**
     * 선착순 참여 요청 (Kafka 방식 - 비동기)
     * POST /api/campaigns/{campaignId}/participation
     *
     * 응답:
     * - 재고 소진: 400 "마감됐습니다"
     * - 선착순 통과: 200 "처리 중입니다" (PENDING 상태, 결과는 /result API로 확인)
     */
    @PostMapping("/{campaignId}/participation")
    public ResponseEntity<ApiResponse<Void>> participate(
            @PathVariable Long campaignId,
            @RequestBody @Valid ParticipationRequest request
    ) {
        boolean accepted = participationService.participate(campaignId, request.getUserId());

        if (!accepted) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail("선착순이 마감됐습니다."));
        }

        return ResponseEntity.ok(
                ApiResponse.success("참여 요청이 접수됐습니다. 잠시 후 결과를 확인해주세요.")
        );
    }

    /**
     * 선착순 참여 결과 조회
     * GET /api/campaigns/{campaignId}/participation/{userId}/result
     *
     * 응답:
     * - PENDING: 아직 처리 중
     * - SUCCESS: 쿠폰 발급 완료
     * - FAIL: 처리 실패
     * - 이력 없음: 404
     */
    @GetMapping("/{campaignId}/participation/{userId}/result")
    public ResponseEntity<ApiResponse<?>> getParticipationResult(
            @PathVariable Long campaignId,
            @PathVariable Long userId
    ) {
        return participationHistoryRepository
                .findTopByCampaignIdAndUserIdOrderByCreatedAtDesc(campaignId, userId)
                .map(history -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("status", history.getStatus().name());
                    data.put("message", resolveMessage(history.getStatus()));
                    data.put("campaignId", campaignId);
                    data.put("userId", userId);
                    return ResponseEntity.ok().<ApiResponse<?>>body(ApiResponse.success(data));
                })
                .orElse(ResponseEntity.status(404)
                        .body(ApiResponse.fail("참여 이력이 없습니다.")));
    }

    /**
     * 상태별 사용자 메시지
     */
    private String resolveMessage(io.eventdriven.batchkafka.domain.entity.ParticipationStatus status) {
        return switch (status) {
            case PENDING -> "처리 중입니다. 잠시 후 다시 확인해주세요.";
            case SUCCESS -> "쿠폰이 발급됐습니다.";
            case FAIL -> "처리 중 오류가 발생했습니다.";
        };
    }

    /**
     * 선착순 참여 요청 (동기 방식 - 성능 비교용, 느리고 불안정함!)
     * POST /api/campaigns/{campaignId}/participation-sync
     */
    @PostMapping("/{campaignId}/participation-sync")
    @Transactional
    public ResponseEntity<ApiResponse<?>> participateSync(
            @PathVariable Long campaignId,
            @RequestBody @Valid ParticipationRequest request
    ) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));

        // Kafka 없이 바로 DB 처리 (동기 - 경합 발생!)
        int updated = campaignRepository.decreaseStockAtomic(campaignId);

        io.eventdriven.batchkafka.domain.entity.ParticipationStatus status;
        if (updated > 0) {
            status = io.eventdriven.batchkafka.domain.entity.ParticipationStatus.SUCCESS;
        } else {
            status = io.eventdriven.batchkafka.domain.entity.ParticipationStatus.FAIL;
        }

        // 이력 저장
        io.eventdriven.batchkafka.domain.entity.ParticipationHistory history =
                new io.eventdriven.batchkafka.domain.entity.ParticipationHistory(
                        campaign, request.getUserId(), status
                );
        participationHistoryRepository.save(history);

        Map<String, Object> data = Map.of(
                "status", status.toString(),
                "method", "SYNC"
        );

        return ResponseEntity.ok(
                ApiResponse.success("참여 처리 완료 (동기 방식)", data)
        );
    }

    /**
     * 캠페인 실시간 현황 조회
     * GET /api/campaigns/{id}/status
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<ApiResponse<?>> getCampaignStatus(@PathVariable Long id) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new CampaignNotFoundException(id));

        // 실시간 집계 (participation_history에서)
        Long successCount = participationHistoryRepository.countSuccessByCampaignId(id);
        Long failCount = participationHistoryRepository.countFailByCampaignId(id);

        // null 체크 (데이터가 없을 경우 대비)
        successCount = successCount != null ? successCount : 0L;
        failCount = failCount != null ? failCount : 0L;
        Long totalCount = successCount + failCount;

        // Redis에서 실시간 재고 조회 (없으면 MySQL fallback)
        Long currentStock = redisStockService.getStock(id);
        if (currentStock == null) {
            currentStock = campaign.getCurrentStock();
        }

        Map<String, Object> data = new HashMap<>();
        data.put("campaignId", campaign.getId());
        data.put("campaignName", campaign.getName());
        data.put("totalStock", campaign.getTotalStock());
        data.put("currentStock", currentStock);
        data.put("successCount", successCount);
        data.put("failCount", failCount);
        data.put("totalParticipation", totalCount);

        // 재고 사용률 계산
        double usageRate = campaign.getTotalStock() > 0
            ? (campaign.getTotalStock() - currentStock) * 100.0 / campaign.getTotalStock()
            : 0.0;
        data.put("stockUsageRate", String.format("%.2f%%", usageRate));

        // ===== 실제 처리 성능 지표 추가 (최근 5초) =====
        java.time.LocalDateTime since = java.time.LocalDateTime.now().minusSeconds(5);
        List<ParticipationHistory> recentRecords = participationHistoryRepository
                .findByCampaignIdAndCreatedAtAfterOrderByCreatedAtAsc(id, since);

        if (!recentRecords.isEmpty()) {
            // 1. 실제 처리 TPS (메시지 처리 속도)
            java.time.LocalDateTime firstProcessed = recentRecords.get(0).getCreatedAt();
            java.time.LocalDateTime lastProcessed = recentRecords.get(recentRecords.size() - 1).getCreatedAt();
            long durationSeconds = java.time.Duration.between(firstProcessed, lastProcessed).getSeconds();
            double actualTps = durationSeconds > 0 ? (double) recentRecords.size() / durationSeconds : 0;

            // 2. 평균 처리 지연시간 (Kafka → DB)
            double avgLatencyMs = recentRecords.stream()
                    .filter(r -> r.getKafkaTimestamp() != null)
                    .mapToLong(r -> {
                        java.time.LocalDateTime kafkaTime = java.time.Instant.ofEpochMilli(r.getKafkaTimestamp())
                                .atZone(java.time.ZoneId.of("Asia/Seoul"))
                                .toLocalDateTime();
                        return java.time.Duration.between(kafkaTime, r.getCreatedAt()).toMillis();
                    })
                    .average()
                    .orElse(0.0);

            Map<String, Object> processingMetrics = new HashMap<>();
            processingMetrics.put("actualTps", Math.round(actualTps * 100.0) / 100.0);
            processingMetrics.put("avgLatencyMs", Math.round(avgLatencyMs * 100.0) / 100.0);
            processingMetrics.put("recentProcessed", recentRecords.size());

            data.put("processingMetrics", processingMetrics);
        } else {
            // 최근 데이터 없음
            Map<String, Object> processingMetrics = new HashMap<>();
            processingMetrics.put("actualTps", 0.0);
            processingMetrics.put("avgLatencyMs", 0.0);
            processingMetrics.put("recentProcessed", 0);

            data.put("processingMetrics", processingMetrics);
        }

        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
