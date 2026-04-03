package org.example.trafficqueuedemobe.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.trafficqueuedemobe.service.QueueService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final QueueService queueService;

    @EventListener
    @SuppressWarnings("unchecked")
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        Map<String, Object> attrs = event.getMessage().getHeaders()
                .get("simpSessionAttributes", Map.class);

        if (attrs != null) {
            String token = (String) attrs.get("token");
            if (token != null) {
                queueService.cancelQueue(token);
                log.info("Session disconnected, removed token {} from queue", token);
            }
        }
    }
}
