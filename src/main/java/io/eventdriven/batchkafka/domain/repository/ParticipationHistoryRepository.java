package io.eventdriven.batchkafka.domain.repository;

import io.eventdriven.batchkafka.domain.entity.ParticipationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipationHistoryRepository extends JpaRepository<ParticipationHistory, Long> {
}
