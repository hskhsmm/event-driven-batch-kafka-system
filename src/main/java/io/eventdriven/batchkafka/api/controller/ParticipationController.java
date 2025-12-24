package io.eventdriven.batchkafka.api.controller;

import io.eventdriven.batchkafka.api.controller.dto.ParticipationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
public class ParticipationController {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @PostMapping("/{campaignId}/participation")
    public String participate(
            @PathVariable Long campaignId,
            @RequestBody ParticipationRequest request
    ) {
        // 메시지 포맷: "campaignId:userId" (간단한 문자열로 전송)
        // 실제 운영 환경에서는 JSON 객체로 직렬화하는 것이 좋습니다.
        String message = campaignId + ":" + request.getUserId();
        
        kafkaTemplate.send("campaign-participation-topic", message);

        return "참여 요청이 접수되었습니다.";
    }
}
