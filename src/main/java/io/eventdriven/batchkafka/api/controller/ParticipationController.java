package io.eventdriven.batchkafka.api.controller;

import io.eventdriven.batchkafka.api.common.ApiResponse;
import io.eventdriven.batchkafka.api.dto.request.ParticipationRequest;
import io.eventdriven.batchkafka.api.exception.business.CampaignNotFoundException;
import io.eventdriven.batchkafka.application.service.ParticipationService;
import io.eventdriven.batchkafka.domain.entity.Campaign;
import io.eventdriven.batchkafka.domain.repository.CampaignRepository;
import io.eventdriven.batchkafka.domain.repository.ParticipationHistoryRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
public class ParticipationController {

    private final ParticipationService participationService;
    private final CampaignRepository campaignRepository;
    private final ParticipationHistoryRepository participationHistoryRepository;

    /**
     * 선착순 참여 요청 (Kafka 방식 - 비동기)
     * POST /api/campaigns/{campaignId}/participation
     */
    @PostMapping("/{campaignId}/participation")
    public ResponseEntity<ApiResponse<Void>> participate(
            @PathVariable Long campaignId,
            @RequestBody @Valid ParticipationRequest request
    ) {
        participationService.participate(campaignId, request.getUserId());
        return ResponseEntity.ok(
                ApiResponse.success("참여 요청이 접수되었습니다.")
        );
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
        Object[] counts = participationHistoryRepository.countStatusByCampaignId(id);
        Long successCount = counts != null && counts.length > 0 ? ((Number) counts[0]).longValue() : 0L;
        Long failCount = counts != null && counts.length > 1 ? ((Number) counts[1]).longValue() : 0L;
        Long totalCount = successCount + failCount;

        Map<String, Object> data = new HashMap<>();
        data.put("campaignId", campaign.getId());
        data.put("campaignName", campaign.getName());
        data.put("totalStock", campaign.getTotalStock());
        data.put("currentStock", campaign.getCurrentStock());
        data.put("successCount", successCount);
        data.put("failCount", failCount);
        data.put("totalParticipation", totalCount);

        // 재고 사용률 계산
        double usageRate = campaign.getTotalStock() > 0
            ? (campaign.getTotalStock() - campaign.getCurrentStock()) * 100.0 / campaign.getTotalStock()
            : 0.0;
        data.put("stockUsageRate", String.format("%.2f%%", usageRate));

        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
