package io.eventdriven.batchkafka.api.controller;

import io.eventdriven.batchkafka.api.controller.dto.ParticipationRequest;
import io.eventdriven.batchkafka.application.service.ParticipationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
public class ParticipationController {

    private final ParticipationService participationService;

    @PostMapping("/{campaignId}/participation")
    public String participate(
            @PathVariable Long campaignId,
            @RequestBody ParticipationRequest request
    ) {
        participationService.participate(campaignId, request.getUserId());
        return "참여 요청이 접수되었습니다.";
    }
}
