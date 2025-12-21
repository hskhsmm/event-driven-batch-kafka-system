package io.eventdriven.batchkafka.api.controller;

import io.eventdriven.batchkafka.api.controller.dto.CampaignCreateRequest;
import io.eventdriven.batchkafka.api.controller.dto.CampaignResponse;
import io.eventdriven.batchkafka.application.service.CampaignService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/campaigns")
@RequiredArgsConstructor
public class AdminController {

    private final CampaignService campaignService;

    @PostMapping
    public ResponseEntity<CampaignResponse> createCampaign(@RequestBody CampaignCreateRequest request) {
        CampaignResponse response = campaignService.createCampaign(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<CampaignResponse>> getCampaigns() {
        return ResponseEntity.ok(campaignService.getCampaigns());
    }
}
