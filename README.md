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

### RabbitMQ - 내구성 보장

- 대기열 진입 시 메시지 발행 (순서 보장, persistence)
- 실제 입장 처리는 스케줄러가 Redis로 직접 수행 (RabbitMQ는 소비하지 않음)
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
3. 스케줄러(5초 주기) → Redis popMin으로 입장 처리
   → /user/queue/enter 로 입장 알림 push
   → /user/queue/position 으로 대기 중인 전원에게 순번 push
   → /topic/queue/status 로 전체 현황 broadcast
4. 내 차례 → /user/queue/enter 로 입장 알림
5. 연결 끊김 → WebSocketEventListener 감지 → 대기열 자동 제거
```

## API 설계

### REST API

#### `GET /api/queue/status/{token}` — 내 대기 상태 조회

**Path Variable**: `token` — join 시 발급받은 토큰

**Response** `200 OK`
```json
{
  "position": 2,                  // 현재 순번 (ACTIVE면 0, NOT_FOUND면 -1)
  "status": "WAITING",            // WAITING | ACTIVE | NOT_FOUND
  "totalWaiting": 10,             // 현재 전체 대기 인원
  "estimatedWaitSeconds": 6       // 예상 대기 시간(초)
}
```

---

#### `GET /api/queue/rank` — 전체 대기열 현황

**Response** `200 OK`
```json
{
  "totalWaiting": 10
}
```

---

### Status 값 정의

| status | position | 의미 |
|---|---|---|
| `WAITING` | 1 이상 | 대기 중. position이 내 순번 |
| `ACTIVE` | 0 | 입장 허용됨. TTL(기본 300초) 내 유효 |
| `NOT_FOUND` | -1 | 토큰 없음 (취소됐거나 TTL 만료) |
| `CANCELLED` | -1 | WebSocket 취소 요청 성공 응답 시 |
| `UPDATE` | (없음) | WebSocket 브로드캐스트 전용. totalWaiting만 유효 |

---

### WebSocket (STOMP)

**연결 엔드포인트**: `ws://localhost:8080/ws` (SockJS)

#### 구독 (서버 → 클라이언트)

**`/topic/queue/status`** — 전체 현황 브로드캐스트 (5초 주기)
```json
{
  "position": 0,
  "status": "UPDATE",
  "totalWaiting": 8,
  "estimatedWaitSeconds": 0
}
```

**`/user/queue/position`** — 내 순번 업데이트

join 직후 응답 (`QueueJoinResponse`):
```json
{
  "token": "550e8400-e29b-41d4-a716-446655440000",
  "position": 3,
  "status": "WAITING"
}
```

스케줄러 자동 push / cancel / heartbeat 응답 (`QueueStatusResponse`):
```json
{
  "position": 2,
  "status": "WAITING",
  "totalWaiting": 10,
  "estimatedWaitSeconds": 10
}
```

**`/user/queue/enter`** — 입장 허용 알림 (스케줄러가 순번 처리 시 push)
```json
{
  "token": "550e8400-e29b-41d4-a716-446655440000",
  "status": "ACTIVE"
}
```

#### 전송 (클라이언트 → 서버)

**`/app/queue/join`** — 대기열 진입
- payload 없음
- userId는 STOMP sessionId로 자동 설정
- 응답은 `/user/queue/position`으로 수신

**`/app/queue/cancel`** — 대기 취소
```json
{ "token": "550e8400-e29b-41d4-a716-446655440000" }
```
- token 생략 시 세션에 저장된 토큰 자동 사용
- 응답은 `/user/queue/position`으로 수신 (`status: CANCELLED` 또는 `NOT_FOUND`)

**`/app/queue/heartbeat`** — 현재 순번 재조회
- payload 없음
- 응답은 `/user/queue/position`으로 수신

## Redis 데이터 구조

| Key | Type | 설명 |
|---|---|---|
| `queue:waiting` | Sorted Set | 대기 순서 관리 (score = priority * 10^12 + timestamp) |
| `queue:active` | Set | 현재 서비스 이용 중인 토큰 |
| `queue:active:{token}` | String (TTL) | 활성 세션 만료 관리 (기본 TTL: 300초) |
| `queue:token:{token}` | String | token → userId 매핑 |

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
│   └── QueueConsumer.java             # RabbitMQ DLQ 소비자 (만료 메시지 로깅)
├── publisher/
│   └── QueuePublisher.java            # RabbitMQ 메시지 발행자
├── listener/
│   └── WebSocketEventListener.java    # WebSocket 연결/해제 이벤트 감지
├── dto/
│   ├── QueueJoinRequest.java          # 대기열 진입 요청 (userId, priority)
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
    implementation 'org.springframework.boot:spring-boot-starter-log4j2'
    implementation 'com.lmax:disruptor:4.0.0'
    compileOnly 'org.projectlombok:lombok:1.18.40'
    annotationProcessor 'org.projectlombok:lombok:1.18.40'
}
```

## 실행 방법

### 사전 준비

| 필수 소프트웨어 | 버전 | 확인 명령어 |
|---|---|---|
| Java | 25+ | `java -version` |
| Docker | 20+ | `docker --version` |
| Docker Compose | v2+ | `docker compose version` |

### 로컬 개발

#### 1단계: Docker 컨테이너 실행 (Redis + RabbitMQ)

Spring Boot 앱이 Redis, RabbitMQ에 의존하므로 **반드시 먼저 실행**해야 한다.

```bash
cd docker && docker compose up -d
```

컨테이너 상태 확인:

```bash
docker compose ps
```

정상이면 아래처럼 두 컨테이너가 `running` 상태여야 한다.

| 컨테이너 | 포트 | 확인 방법 |
|---|---|---|
| Redis | `127.0.0.1:6379` | `docker compose logs redis` |
| RabbitMQ | `127.0.0.1:5672` (AMQP), `127.0.0.1:15672` (Management UI) | http://localhost:15672 (계정: `queue_admin` / `queue_rabbit_pass!`) |

#### 2단계: Spring Boot 앱 실행

```bash
./gradlew bootRun
```

환경변수 없이 그대로 실행하면 된다. `application.yml`과 `docker-compose.yml`에 동일한 기본값이 설정되어 있어서 별도 설정 없이 연동된다.

#### 3단계: 동작 확인

- REST API: `GET http://localhost:8080/api/queue/rank`
- WebSocket: `ws://localhost:8080/ws` (STOMP)
- RabbitMQ 관리 UI: http://localhost:15672

### 종료

```bash
# Spring Boot 앱: Ctrl+C로 종료

# Docker 컨테이너 종료 (데이터 유지)
cd docker && docker compose down

# Docker 컨테이너 종료 + 볼륨 삭제 (데이터 초기화)
cd docker && docker compose down -v
```

### 운영 배포

운영 환경에서는 반드시 환경변수로 민감 정보를 주입해야 한다. 기본값은 로컬 개발용이므로 그대로 사용하면 안 된다.

```bash
REDIS_PASSWORD=실제비밀번호 \
RABBITMQ_USER=실제계정 \
RABBITMQ_PASS=실제비밀번호 \
RABBITMQ_VHOST=실제vhost \
./gradlew bootRun
```

## 환경변수

| 변수명 | 용도 | 기본값 (로컬용) | 사용처 |
|---|---|---|---|
| `REDIS_HOST` | Redis 호스트 | `localhost` | application.yml |
| `REDIS_PORT` | Redis 포트 | `6379` | application.yml |
| `REDIS_PASSWORD` | Redis 비밀번호 | `queue_redis_pass!` | docker-compose, application.yml |
| `RABBITMQ_HOST` | RabbitMQ 호스트 | `localhost` | application.yml |
| `RABBITMQ_PORT` | RabbitMQ 포트 | `5672` | application.yml |
| `RABBITMQ_USER` | RabbitMQ 계정 | `queue_admin` | docker-compose, application.yml |
| `RABBITMQ_PASS` | RabbitMQ 비밀번호 | `queue_rabbit_pass!` | docker-compose, application.yml |
| `RABBITMQ_VHOST` | RabbitMQ 가상호스트 | `queue_vhost` | docker-compose, application.yml |
| `FE_URL` | 프론트엔드 URL (CORS 허용) | `http://localhost:3000` | application.yml |

### 큐 동작 파라미터

`application.yml`에서 조정 가능한 큐 설정값:

| 설정 키 | 기본값 | 설명 |
|---|---|---|
| `queue.process.batch-size` | `1` | 스케줄러 1회 처리 인원 수 |
| `queue.active.ttl-seconds` | `300` | 활성 세션 유지 시간 (초) |
| `queue.message.ttl-ms` | `600000` | RabbitMQ 메시지 만료 시간 (ms, 기본 10분) |

스케줄러는 **5초 주기**로 실행되며(`QueueScheduler.java`), 처리된 사용자에게 입장 알림을 전송하고 대기 중인 전원에게 순번을 push하며 전체 현황을 broadcast한다.

### 동작 원리

- `application.yml`에서 `${REDIS_PASSWORD:queue_redis_pass!}` 형태로 선언
- 환경변수 `REDIS_PASSWORD`가 **있으면** → 해당 값 사용
- 환경변수가 **없으면** → `:` 뒤의 기본값(`queue_redis_pass!`) 사용
- `docker-compose.yml`도 동일한 패턴(`${REDIS_PASSWORD:-queue_redis_pass!}`)으로 같은 기본값을 공유
- 따라서 로컬에서는 아무 설정 없이 양쪽이 같은 비밀번호로 연결됨

## 보안 설정

### Redis

- `requirepass`로 비밀번호 인증 적용
- `FLUSHALL`, `FLUSHDB`, `CONFIG`, `KEYS` 명령어 비활성화
- `maxmemory 256mb` + `allkeys-lru` eviction 정책
- 포트 `127.0.0.1`로 로컬 바인딩 (외부 접근 차단)

### RabbitMQ

- 기본 `guest/guest` 계정 대신 전용 계정 사용
- 전용 vhost로 격리
- Management UI 포함 모든 포트 `127.0.0.1`로 로컬 바인딩
- 큐 메시지 TTL 설정 (기본 10분, 만료 시 DLQ로 라우팅)

## TODO

### Redis 데이터 정리 미흡

| Key | 문제 |
|---|---|
| `queue:active` (Set) | 입장 처리 후 멤버 삭제 로직 없음. 무한 증가 |
| `queue:token:{token}` (String) | 입장 완료 시 미삭제, TTL 없음. `cancelQueue()` 호출 시에만 삭제됨 |

`queue:active:{token}` (String)은 TTL 300초로 자동 만료되지만, `queue:active` Set 자체의 멤버와 `queue:token:{token}`은 정리되지 않음.

### RabbitMQ `queue.waiting` 미소비

스케줄러가 Redis로 직접 처리하므로 `queue.waiting`에 발행된 메시지는 소비되지 않고 TTL(10분) 만료 후 DLQ로 이동함. 트래픽이 많을 경우 최대 10분치 메시지가 상시 체류함.

## 구현 순서

1. 인프라 설정 (Docker Compose - Redis, RabbitMQ)
2. Config 클래스 (RabbitMQ, Redis, WebSocket, CORS)
3. QueueService - 대기열 핵심 로직
4. QueuePublisher / QueueConsumer - RabbitMQ 연동
5. QueueWebSocketController - WebSocket 메시지 핸들러
6. WebSocketEventListener - 연결/해제 감지
7. QueueRestController - REST API
8. 테스트
