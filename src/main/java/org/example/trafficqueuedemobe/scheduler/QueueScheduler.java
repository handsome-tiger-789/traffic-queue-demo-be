package org.example.trafficqueuedemobe.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.trafficqueuedemobe.dto.QueueStatusResponse;
import org.example.trafficqueuedemobe.service.QueueService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueScheduler {

    private final QueueService queueService;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${queue.process.batch-size:5}")
    private int batchSize;

    @Scheduled(fixedRate = 5000)
    public void processQueue() {
        List<String> processedTokens = queueService.processQueue(batchSize);

        // Notify each processed user that they can enter
        for (String token : processedTokens) {
            String userId = queueService.getUserIdByToken(token);
            if (userId != null) {
                log.info("Sending /queue/enter to userId={}, token={}", userId, token);
                messagingTemplate.convertAndSendToUser(userId, "/queue/enter",
                        Map.of("token", token, "status", "ACTIVE"));
                log.info("Sent /queue/enter to userId={}, token={}", userId, token);
            } else {
                log.warn("userId not found for token={}, enter message skipped", token);
            }
        }

        // Get current waiting list after processing
        List<String> waitingTokens = queueService.getAllWaitingTokens();
        long totalWaiting = waitingTokens.size();

        // Broadcast overall queue status to all subscribers
        messagingTemplate.convertAndSend("/topic/queue/status",
                QueueStatusResponse.builder()
                        .totalWaiting(totalWaiting)
                        .status("UPDATE")
                        .build());

        log.debug("Broadcast /topic/queue/status: totalWaiting={}", totalWaiting);

        // Push individual position to each waiting user
        for (int i = 0; i < waitingTokens.size(); i++) {
            String waitingToken = waitingTokens.get(i);
            String waitingUserId = queueService.getUserIdByToken(waitingToken);
            if (waitingUserId != null) {
                long position = i + 1;
                messagingTemplate.convertAndSendToUser(waitingUserId, "/queue/position",
                        QueueStatusResponse.builder()
                                .position(position)
                                .status("WAITING")
                                .totalWaiting(totalWaiting)
                                .estimatedWaitSeconds((position / batchSize) * 5)
                                .build());
            }
        }

        if (!processedTokens.isEmpty()) {
            log.info("Processed {} users, {} still waiting", processedTokens.size(), totalWaiting);
        }
    }
}
