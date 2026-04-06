package org.example.trafficqueuedemobe.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.trafficqueuedemobe.dto.QueueJoinRequest;
import org.example.trafficqueuedemobe.dto.QueueJoinResponse;
import org.example.trafficqueuedemobe.dto.QueueStatusResponse;
import org.example.trafficqueuedemobe.publisher.QueuePublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final String WAITING_KEY = "queue:waiting";
    private static final String ACTIVE_SET_KEY = "queue:active";
    private static final String ACTIVE_TOKEN_PREFIX = "queue:active:";
    private static final String TOKEN_USER_PREFIX = "queue:token:";
    private static final long ESTIMATED_PROCESS_INTERVAL_SECONDS = 3;

    private final StringRedisTemplate redisTemplate;
    private final QueuePublisher queuePublisher;

    @Value("${queue.process.batch-size:5}")
    private int batchSize;

    @Value("${queue.active.ttl-seconds:300}")
    private long activeTtlSeconds;

    public QueueJoinResponse joinQueue(String userId) {
        return joinQueue(userId, 0);
    }

    public QueueJoinResponse joinQueue(QueueJoinRequest request) {
        return joinQueue(request.getUserId(), request.getPriority());
    }

    private QueueJoinResponse joinQueue(String userId, int priority) {
        String token = UUID.randomUUID().toString();

        // score: lower priority value = higher priority, timestamp for ordering within same priority
        double score = priority * 1_000_000_000_000L + System.currentTimeMillis();
        redisTemplate.opsForZSet().add(WAITING_KEY, token, score);
    
        // Map token -> userId
        redisTemplate.opsForValue().set(TOKEN_USER_PREFIX + token, userId);
    
        // Publish to RabbitMQ for durability
        queuePublisher.publish(token, userId, priority);
    
        Long position = redisTemplate.opsForZSet().rank(WAITING_KEY, token);
        long pos = position != null ? position + 1 : 1;
    
        log.info("User {} joined queue with token {}, position {}", userId, token, pos);
    
        return QueueJoinResponse.builder()
                .token(token)
                .position(pos)
                .status("WAITING")
                .build();
    }
    public long getPosition(String token) {
        Long rank = redisTemplate.opsForZSet().rank(WAITING_KEY, token);
        return rank != null ? rank + 1 : -1;
    }

    public boolean cancelQueue(String token) {
        Long removed = redisTemplate.opsForZSet().remove(WAITING_KEY, token);
        boolean wasActive = Boolean.TRUE.equals(redisTemplate.hasKey(ACTIVE_TOKEN_PREFIX + token));
        redisTemplate.delete(TOKEN_USER_PREFIX + token);
        redisTemplate.delete(ACTIVE_TOKEN_PREFIX + token);
        redisTemplate.opsForSet().remove(ACTIVE_SET_KEY, token);
        if ((removed != null && removed > 0) || wasActive) {
            log.info("Token {} cancelled from queue", token);
            return true;
        }
        return false;
    }

    public List<String> processQueue(int size) {
        Set<ZSetOperations.TypedTuple<String>> entries =
                redisTemplate.opsForZSet().popMin(WAITING_KEY, size);

        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<String> processedTokens = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> entry : entries) {
            String token = entry.getValue();
            if (token == null) continue;

            // Move to active set
            redisTemplate.opsForSet().add(ACTIVE_SET_KEY, token);
            redisTemplate.opsForValue().set(
                    ACTIVE_TOKEN_PREFIX + token, "active",
                    Duration.ofSeconds(activeTtlSeconds)
            );

            processedTokens.add(token);
            log.info("Token {} entered active state", token);
        }

        return processedTokens;
    }

    public boolean isActive(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(ACTIVE_TOKEN_PREFIX + token));
    }

    public QueueStatusResponse getQueueStatus(String token) {
        // Check if active
        if (isActive(token)) {
            return QueueStatusResponse.builder()
                    .position(0)
                    .status("ACTIVE")
                    .totalWaiting(getTotalWaiting())
                    .estimatedWaitSeconds(0)
                    .build();
        }

        long position = getPosition(token);
        if (position == -1) {
            return QueueStatusResponse.builder()
                    .position(-1)
                    .status("NOT_FOUND")
                    .totalWaiting(getTotalWaiting())
                    .estimatedWaitSeconds(0)
                    .build();
        }

        long estimatedWait = (position / batchSize) * ESTIMATED_PROCESS_INTERVAL_SECONDS;

        return QueueStatusResponse.builder()
                .position(position)
                .status("WAITING")
                .totalWaiting(getTotalWaiting())
                .estimatedWaitSeconds(estimatedWait)
                .build();
    }

    public long getTotalWaiting() {
        Long size = redisTemplate.opsForZSet().size(WAITING_KEY);
        return size != null ? size : 0;
    }

    public String getUserIdByToken(String token) {
        return redisTemplate.opsForValue().get(TOKEN_USER_PREFIX + token);
    }

    public List<String> getAllWaitingTokens() {
        Set<String> tokens = redisTemplate.opsForZSet().range(WAITING_KEY, 0, -1);
        return tokens != null ? new ArrayList<>(tokens) : List.of();
    }
}
