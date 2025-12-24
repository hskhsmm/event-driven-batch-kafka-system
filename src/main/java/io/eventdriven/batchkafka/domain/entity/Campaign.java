package io.eventdriven.batchkafka.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "campaign")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) //리플렉션
public class Campaign extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "total_stock", nullable = false)
    private Long totalStock;

    @Column(name = "current_stock", nullable = false)
    private Long currentStock;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CampaignStatus status;

    public Campaign(String name, Long totalStock) {
        this.name = name;
        this.totalStock = totalStock;
        this.currentStock = totalStock;
        this.status = CampaignStatus.OPEN;
    }

    // 밑의 메서드는 간단한 규칙이니까 내부 로직으로

    public void decreaseStock() {
        if (this.currentStock <= 0) {
            throw new IllegalStateException("재고가 부족합니다.");
        }
        this.currentStock--;
    }


    public void close() {
        this.status = CampaignStatus.CLOSED;
    }
}
