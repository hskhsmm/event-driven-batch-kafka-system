package io.eventdriven.batchkafka.api.controller;

import io.eventdriven.batchkafka.api.common.ApiResponse;
import io.eventdriven.batchkafka.api.dto.request.ParticipationRequest;
import io.eventdriven.batchkafka.application.service.ParticipationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowCredentials = "true")
public class ParticipationController {

    private final ParticipationService participationService;

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
}
