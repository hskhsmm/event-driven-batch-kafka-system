package io.eventdriven.batchkafka.domain.repository;

import io.eventdriven.batchkafka.domain.entity.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {
}
