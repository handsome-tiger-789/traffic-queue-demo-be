package org.example.trafficqueuedemobe.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.trafficqueuedemobe.dto.QueueJoinRequest;
import org.example.trafficqueuedemobe.dto.QueueJoinResponse;
import org.example.trafficqueuedemobe.dto.QueueStatusResponse;
import org.example.trafficqueuedemobe.service.QueueService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class QueueWebSocketController {

    private final QueueService queueService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/queue/join")
    public void joinQueue(@Payload QueueJoinRequest request, SimpMessageHeaderAccessor headerAccessor) {
        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            userId = headerAccessor.getSessionId();
        }

        QueueJoinResponse response = queueService.joinQueue(userId, request.getPriority());

        // Store token in WebSocket session for disconnect cleanup
        headerAccessor.getSessionAttributes().put("token", response.getToken());

        // Send position to the specific user
        String sessionId = headerAccessor.getSessionId();
        messagingTemplate.convertAndSendToUser(sessionId, "/queue/position", response,
                createHeaders(sessionId));

        log.info("User {} joined queue via WebSocket, token: {}", userId, response.getToken());
    }

    @MessageMapping("/queue/cancel")
    public void cancelQueue(@Payload Map<String, String> payload, SimpMessageHeaderAccessor headerAccessor) {
        String token = payload.get("token");
        if (token == null) {
            token = (String) headerAccessor.getSessionAttributes().get("token");
        }

        if (token != null) {
            boolean cancelled = queueService.cancelQueue(token);
            String sessionId = headerAccessor.getSessionId();
            messagingTemplate.convertAndSendToUser(sessionId, "/queue/position",
                    QueueStatusResponse.builder()
                            .position(-1)
                            .status(cancelled ? "CANCELLED" : "NOT_FOUND")
                            .totalWaiting(queueService.getTotalWaiting())
                            .estimatedWaitSeconds(0)
                            .build(),
                    createHeaders(sessionId));
        }
    }

    @MessageMapping("/queue/heartbeat")
    public void heartbeat(SimpMessageHeaderAccessor headerAccessor) {
        String token = (String) headerAccessor.getSessionAttributes().get("token");
        if (token != null) {
            QueueStatusResponse status = queueService.getQueueStatus(token);
            String sessionId = headerAccessor.getSessionId();
            messagingTemplate.convertAndSendToUser(sessionId, "/queue/position", status,
                    createHeaders(sessionId));
        }
    }

    private org.springframework.messaging.MessageHeaders createHeaders(String sessionId) {
        org.springframework.messaging.simp.SimpMessageHeaderAccessor accessor =
                org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(
                        org.springframework.messaging.simp.SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        return accessor.getMessageHeaders();
    }
}
