package org.example.trafficqueuedemobe.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.trafficqueuedemobe.config.RabbitMQConfig;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueuePublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publish(String token, String userId, int priority) {
        String message = token + ":" + userId;

        MessagePostProcessor postProcessor = msg -> {
            msg.getMessageProperties().setPriority(priority);
            return msg;
        };

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE,
                RabbitMQConfig.ROUTING_KEY,
                message,
                postProcessor
        );

        log.info("Published to RabbitMQ: token={}, userId={}, priority={}", token, userId, priority);
    }
}
