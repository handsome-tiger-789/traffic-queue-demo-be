# Traffic Queue Demo - Backend

대량 트래픽 처리를 위한 대기열 시스템 백엔드

## 아키텍처

```
[React FE] ←WebSocket(STOMP)→ [Spring Boot BE] ←→ [RabbitMQ] (큐 처리 엔진)
                                                ←→ [Redis]    (캐시 + 실시간 조회)
```

## 기술 스택

| 기술 | 버전 | 용도 |
|---|---|---|
| Java | 25 | 언어 |
| Spring Boot | 4.0.5 | 프레임워크 |
| RabbitMQ | - | 대기열 처리 엔진 (메시지 보장, DLQ, 우선순위) |
| Redis | - | 캐시 + 순번 조회 + 활성 세션 관리 |
| WebSocket (STOMP) | - | 실시간 양방향 통신 |
| Lombok | - | 보일러플레이트 코드 제거 |

## 역할 분리

### RabbitMQ - 대기열 처리 엔진

- 대기열 진입 시 메시지 발행 (순서 보장, persistence)
- Consumer가 N명씩 소비하여 입장 처리
- DLQ로 만료/실패 메시지 자동 라우팅
- Priority Queue로 우선순위 입장 지원

### Redis - 캐시 + 실시간 조회

- Sorted Set(ZSET)으로 순번 캐싱 → `ZRANK`로 즉시 조회
- 활성 세션 저장 (TTL 기반 만료 관리)
- Redis가 죽어도 RabbitMQ에 원본 보존 → 복구 후 재동기화 가능

### WebSocket (STOMP) - 실시간 통신

- 양방향 통신으로 대기열 상태 실시간 업데이트
- 연결 끊김 자동 감지 → 대기열에서 자동 제거
- STOMP 내장 하트비트 지원

## 사용자 흐름

```
1. 사용자 접속 → WebSocket 연결 (STOMP)
2. /app/queue/join 전송 → RabbitMQ 발행 + Redis 순번 캐싱
3. 스케줄러 → RabbitMQ 소비 → Redis 업데이트
   → /user/queue/position 으로 순번 push
   → /topic/queue/status 로 전체 현황 broadcast
4. 내 차례 → /user/queue/enter 로 입장 알림
5. 연결 끊김 → WebSocketEventListener 감지 → 대기열 자동 제거
```

## API 설계

### REST API

| Method | Endpoint | 설명 |
|---|---|---|
| `GET` | `/api/queue/status/{token}` | 현재 순번/상태 조회 |
| `GET` | `/api/queue/rank` | 전체 대기열 현황 |

### WebSocket 메시지

**서버 → 클라이언트 (구독)**

| Destination | 내용 |
|---|---|
| `/topic/queue/status` | 전체 대기열 현황 (총 대기 인원 등) |
| `/user/queue/position` | 내 순번 + 상태 실시간 업데이트 |
| `/user/queue/enter` | 입장 허용 알림 |

**클라이언트 → 서버 (전송)**

| Destination | 내용 |
|---|---|
| `/app/queue/join` | 대기열 진입 요청 |
| `/app/queue/cancel` | 대기 취소 |
| `/app/queue/heartbeat` | 연결 유지 확인 |

## Redis 데이터 구조

| Key | Type | 설명 |
|---|---|---|
| `queue:waiting` | Sorted Set | 대기 순서 관리 (score = 진입 시각) |
| `queue:active` | Set | 현재 서비스 이용 중인 토큰 |
| `queue:active:{token}` | String (TTL) | 활성 세션 만료 관리 |

## RabbitMQ 구조

| 구성 요소 | 이름 | 설명 |
|---|---|---|
| Exchange | `queue.exchange` | 대기열 메시지 라우팅 |
| Queue | `queue.waiting` | 대기열 메인 큐 |
| DLQ | `queue.waiting.dlq` | 만료/실패 메시지 처리 |
| Priority | 0-10 | 우선순위 입장 지원 |

## 패키지 구조

```
org.example.trafficqueuedemobe
├── config/
│   ├── RabbitMQConfig.java            # Exchange, Queue, DLQ, Priority 설정
│   ├── RedisConfig.java               # Redis 연결 설정
│   ├── WebSocketConfig.java           # STOMP + WebSocket 설정
│   └── CorsConfig.java               # React FE CORS 허용
├── controller/
│   ├── QueueRestController.java       # REST API (상태 조회 등)
│   └── QueueWebSocketController.java  # WebSocket 메시지 핸들러
├── service/
│   └── QueueService.java              # 대기열 핵심 로직
├── consumer/
│   └── QueueConsumer.java             # RabbitMQ 메시지 소비자 (입장 처리)
├── publisher/
│   └── QueuePublisher.java            # RabbitMQ 메시지 발행자
├── listener/
│   └── WebSocketEventListener.java    # WebSocket 연결/해제 이벤트 감지
├── dto/
│   ├── QueueJoinResponse.java         # 토큰 + 순번 응답
│   └── QueueStatusResponse.java       # 현재 상태 응답
└── TrafficQueueDemoBeApplication.java
```

## 의존성

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    implementation 'org.springframework.boot:spring-boot-starter-amqp'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-websocket'
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
```

## 구현 순서

1. 인프라 설정 (Docker Compose - Redis, RabbitMQ)
2. Config 클래스 (RabbitMQ, Redis, WebSocket, CORS)
3. QueueService - 대기열 핵심 로직
4. QueuePublisher / QueueConsumer - RabbitMQ 연동
5. QueueWebSocketController - WebSocket 메시지 핸들러
6. WebSocketEventListener - 연결/해제 감지
7. QueueRestController - REST API
8. 테스트
