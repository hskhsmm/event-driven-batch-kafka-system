package io.eventdriven.batchkafka.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "participation_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParticipationHistory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ParticipationStatus status;

    public ParticipationHistory(Campaign campaign, Long userId, ParticipationStatus status) {
        this.campaign = campaign;
        this.userId = userId;
        this.status = status;
    }
}
