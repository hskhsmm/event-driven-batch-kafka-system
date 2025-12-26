package io.eventdriven.batchkafka.domain.repository;

import io.eventdriven.batchkafka.domain.entity.CampaignStats;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CampaignStatsRepository extends JpaRepository<CampaignStats, Long> {
    List<CampaignStats> findByStatsDate(LocalDate statsDate);
}
