package io.eventdriven.batchkafka.api.controller;

import io.eventdriven.batchkafka.api.common.ApiResponse;
import io.eventdriven.batchkafka.application.service.ParticipationTestService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 부하 테스트용 API
 * - 프론트엔드에서 버튼 클릭으로 대규모 트래픽 시뮬레이션
 * - 실제 사용자 모집 없이 선착순 시스템 동작 확인
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/test")
@RequiredArgsConstructor
public class TestController {

    private final ParticipationTestService participationTestService;

    /**
     * 부하 테스트 요청 DTO
     */
    public record BulkParticipateRequest(
            @Min(value = 1, message = "요청 수는 최소 1개 이상이어야 합니다.")
            @Max(value = 100000, message = "요청 수는 최대 100,000개까지 가능합니다.")
            int count,

            Long campaignId
    ) {}

    /**
     * 응답 DTO
     */
    public record BulkTestResponse(
            Long campaignId,
            int requestCount,
            String status
    ) {}

    /**
     * 대량 참여 시뮬레이션 API
     *
     * POST /api/admin/test/participate-bulk
     *
     * 예시:
     * {
     *   "count": 10000,
     *   "campaignId": 1
     * }
     *
     * @param request count (요청 수), campaignId (캠페인 ID)
     * @return 시뮬레이션 시작 메시지
     */
    @PostMapping("/participate-bulk")
    public ResponseEntity<ApiResponse<BulkTestResponse>> simulateParticipation(
            @RequestBody BulkParticipateRequest request) {

        log.info("🚀 부하 테스트 시작 - 캠페인 ID: {}, 요청 수: {:,}",
                request.campaignId(), request.count());

        // 비동기로 서비스 실행 (API 응답은 즉시 반환)
        participationTestService.simulate(request.campaignId(), request.count());

        BulkTestResponse response = new BulkTestResponse(
                request.campaignId(),
                request.count(),
                "시뮬레이션이 백그라운드에서 실행 중입니다. 통계 페이지에서 실시간 결과를 확인하세요."
        );

        String message = String.format("%,d건의 참여 요청 시뮬레이션을 시작했습니다.", request.count());

        return ResponseEntity.ok(ApiResponse.success(message, response));
    }
}
