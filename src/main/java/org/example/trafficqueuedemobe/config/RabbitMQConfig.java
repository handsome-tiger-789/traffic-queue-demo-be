package org.example.trafficqueuedemobe.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${queue.message.ttl-ms:600000}")
    private int messageTtlMs;

    public static final String EXCHANGE = "queue.exchange";
    public static final String WAITING_QUEUE = "queue.waiting";
    public static final String DLQ = "queue.waiting.dlq";
    public static final String ROUTING_KEY = "queue.waiting.key";
    public static final String DLQ_ROUTING_KEY = "queue.waiting.dlq.key";

    @Bean
    DirectExchange queueExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    Queue waitingQueue() {
        return QueueBuilder.durable(WAITING_QUEUE)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .withArgument("x-max-priority", 10)
                .withArgument("x-message-ttl", messageTtlMs)
                .build();
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Binding waitingBinding(Queue waitingQueue, DirectExchange queueExchange) {
        return BindingBuilder.bind(waitingQueue).to(queueExchange).with(ROUTING_KEY);
    }

    @Bean
    Binding dlqBinding(Queue deadLetterQueue, DirectExchange queueExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(queueExchange).with(DLQ_ROUTING_KEY);
    }
}
