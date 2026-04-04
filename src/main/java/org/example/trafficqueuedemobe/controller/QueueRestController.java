package org.example.trafficqueuedemobe.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.trafficqueuedemobe.dto.QueueStatusResponse;
import org.example.trafficqueuedemobe.service.QueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueRestController {

    private final QueueService queueService;

    @GetMapping("/status/{token}")
    public ResponseEntity<QueueStatusResponse> getStatus(@PathVariable String token) {
        log.info("큐 상태 요청 받음: token={}", token);
        QueueStatusResponse status = queueService.getQueueStatus(token);
        log.info("큐 상태 응답 완료: token={}, status={}", token, status.getStatus());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/rank")
    public ResponseEntity<Map<String, Long>> getRank() {
        log.info("현재 대기열 순위 요청 받음");
        long totalWaiting = queueService.getTotalWaiting();
        log.info("현재 대기열 순위 응답 완료: totalWaiting={}", totalWaiting);
        return ResponseEntity.ok(Map.of("totalWaiting", totalWaiting));
    }
}
