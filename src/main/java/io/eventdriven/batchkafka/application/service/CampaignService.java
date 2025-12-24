package io.eventdriven.batchkafka.application.service;

import io.eventdriven.batchkafka.api.dto.request.CampaignCreateRequest;
import io.eventdriven.batchkafka.api.dto.response.CampaignResponse;
import io.eventdriven.batchkafka.domain.entity.Campaign;
import io.eventdriven.batchkafka.domain.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CampaignService {

    private final CampaignRepository campaignRepository;

    @Transactional
    public CampaignResponse createCampaign(CampaignCreateRequest request) {
        Campaign campaign = new Campaign(request.getName(), request.getTotalStock());
        Campaign savedCampaign = campaignRepository.save(campaign);
        return new CampaignResponse(savedCampaign);
    }

    public List<CampaignResponse> getCampaigns() {
        return campaignRepository.findAll().stream()
                .map(CampaignResponse::new)
                .collect(Collectors.toList());
    }
}
