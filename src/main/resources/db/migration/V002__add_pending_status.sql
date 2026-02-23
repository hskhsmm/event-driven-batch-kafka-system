-- PENDING 상태 추가: 선착순 통과 후 Consumer 확정 전 중간 상태
ALTER TABLE participation_history
    MODIFY COLUMN status ENUM('PENDING', 'SUCCESS', 'FAIL') NOT NULL;
