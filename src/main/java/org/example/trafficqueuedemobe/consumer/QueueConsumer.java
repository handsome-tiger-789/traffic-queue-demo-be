package org.example.trafficqueuedemobe.consumer;

import lombok.extern.slf4j.Slf4j;
import org.example.trafficqueuedemobe.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class QueueConsumer {

    @RabbitListener(queues = RabbitMQConfig.DLQ)
    public void handleDeadLetter(String message) {
        log.warn("Dead letter received: {}", message);
    }
}
