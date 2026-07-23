# Redis và Cache của Feed Service

## Mục đích và phạm vi

Feed Service dùng cache hai tầng cho một post theo ID và danh sách feed phân trang bằng cursor. Cache là dữ liệu có thể tái tạo: Cassandra vẫn là nguồn dữ liệu chính của post/feed, còn Redis không phải database nghiệp vụ.

Redis lỗi không làm Feed Service lỗi chỉ vì cache. Ứng dụng bỏ qua cache và đọc Cassandra; lỗi Redis được ghi nhận bằng metric. Redis trong Docker Compose chỉ phục vụ nội bộ, không có persistence, không mở cổng host và dùng chính sách bộ nhớ allkeys-lfu.

## Kiến trúc và luồng dữ liệu

~~~text
GET post hoặc page
        |
        v
Caffeine L1 (bộ nhớ của từng Feed Service process)
        | miss
        v
Redis L2 (dùng chung giữa các process)
        | miss / Redis lỗi
        v
Cassandra (nguồn dữ liệu)
~~~

Khi L2 hit, giá trị được nạp lại vào L1. Khi L1 hit, ứng dụng không gọi Redis hoặc Cassandra.

Khi tạo post, Feed Service ghi Cassandra, tăng revision Redis của feed global, publish một Redis Pub/Sub invalidation chứa exact key của post, rồi lưu post mới vào cache. Mỗi Feed Service instance nhận thông điệp và xóa exact key đó khỏi L1 của chính nó.

Page key theo revision cũ không bị xóa theo prefix; chúng không còn được request mới sử dụng và hết hạn theo TTL. Application không dùng Redis KEYS hoặc SCAN để invalidation. Lệnh redis-cli --scan bên dưới chỉ dùng để người vận hành kiểm tra local.

## Key, TTL và revision

| Loại | Dạng key | Ghi chú |
| --- | --- | --- |
| Post | devconnect:{environment}:feed:v1:post:{postId} | JSON của một post |
| Revision | devconnect:{environment}:feed:v1:revision:global | Khởi tạo là 1, tăng khi tạo post |
| Page | devconnect:{environment}:feed:v1:page:global:rev:{revision}:size:{size}:cursor:{hash} | Cursor đầu là first; cursor khác dùng 16 byte đầu SHA-256 ở dạng hex |

| Dữ liệu | L1 Caffeine | L2 Redis |
| --- | ---: | ---:|
| Post | 45 giây | 5 phút |
| Page | 10 giây | 60 giây |

TTL có jitter mặc định 0% đến 10% để tránh nhiều key hết hạn cùng lúc. L1 có giới hạn mặc định 10.000 entry và có thể evict sớm khi đầy.

## Cấu hình

| Biến môi trường | Spring property | Mặc định |
| --- | --- | --- |
| REDIS_HOST | spring.data.redis.host | localhost |
| REDIS_PORT | spring.data.redis.port | 6379 |
| REDIS_CONNECT_TIMEOUT | spring.data.redis.connect-timeout | 500ms |
| REDIS_COMMAND_TIMEOUT | spring.data.redis.timeout | 500ms |
| CACHE_ENABLED | app.cache.enabled | true |
| CACHE_ENVIRONMENT | app.cache.environment | local |
| CACHE_L1_MAXIMUM_SIZE | app.cache.l1-maximum-size | 10000 |
| CACHE_POST_L1_TTL / CACHE_POST_L2_TTL | TTL post | 45s / 5m |
| CACHE_PAGE_L1_TTL / CACHE_PAGE_L2_TTL | TTL page | 10s / 60s |
| CACHE_TTL_JITTER_PERCENT | app.cache.ttl-jitter-percent | 10 |
| CACHE_INVALIDATION_CHANNEL | app.cache.invalidation-channel | devconnect:cache:invalidation |

Đặt CACHE_ENABLED=false để dùng NoOpCacheService: ứng dụng vẫn chạy, nhưng mọi read đi thẳng tới Cassandra. Không dùng CACHE_ENVIRONMENT=local của Compose trong production.

Compose cấu hình Feed Service kết nối redis:6379. Xem thêm [Docker operations](DOCKER.md#redis-cache-operations).

## Kiểm tra thủ công ở local

### 1. Khởi động và kiểm tra Redis

~~~bash
docker network create devconnect-network 2>/dev/null || true
docker compose up -d --build
docker compose ps redis feed-service api-gateway
docker compose exec redis redis-cli ping
~~~

Kết quả cuối phải là PONG. Chờ các application service healthy trước khi gọi Gateway.

### 2. Tạo hoặc chọn một post

Cần một user ACTIVE. Nếu môi trường chưa có user, tạo một user mới:

~~~bash
curl -fsS -X POST http://localhost:8090/api/users \
  -H 'Content-Type: application/json' \
  -d '{"userId":"cache-demo-user","status":"ACTIVE","email":"cache-demo-user@example.com"}'
~~~

Tạo post và đặt postId trả về vào POST_ID:

~~~bash
curl -fsS -X POST http://localhost:8090/api/feed/posts \
  -H 'Content-Type: application/json' \
  -d '{"authorId":"cache-demo-user","content":"Redis cache verification"}'

export POST_ID='<postId từ response>'
~~~

Nếu user/email đã tồn tại, dùng một user ACTIVE khác.

### 3. Xác nhận cache post và TTL

Lần đọc đầu nạp cache; lần kế tiếp có thể trúng L1. Cả hai request phải thành công:

~~~bash
curl -fsS "http://localhost:8090/api/feed/posts/$POST_ID"
curl -fsS "http://localhost:8090/api/feed/posts/$POST_ID"
docker compose exec redis redis-cli --scan \
  --pattern "devconnect:local:feed:v1:post:$POST_ID"
docker compose exec redis redis-cli TTL "devconnect:local:feed:v1:post:$POST_ID"
~~~

Scan phải in đúng key post. TTL phải là số dương; không cần đúng 300 giây vì có jitter.

### 4. Xác nhận cache page và revision

~~~bash
curl -fsS 'http://localhost:8090/api/feed/posts?pageSize=20'
docker compose exec redis redis-cli GET \
  'devconnect:local:feed:v1:revision:global'
docker compose exec redis redis-cli --scan \
  --pattern 'devconnect:local:feed:v1:page:global:*'
~~~

GET page tạo revision khi chưa có và có thể tạo page key. Sau khi tạo thêm post, revision tăng; page key cũ không còn được request mới dùng và hết hạn theo TTL.

### 5. Quan sát metric và fail-open

Feed actuator không được Gateway route ra public. Khi chạy Compose, dùng một container curl trong cùng network:

~~~bash
docker run --rm --network devconnect-network curlimages/curl:8.12.1 \
  -fsS http://feed-service:8082/actuator/metrics/feed.cache.redis.errors
docker run --rm --network devconnect-network curlimages/curl:8.12.1 \
  -fsS http://feed-service:8082/actuator/metrics/feed.cache.invalidation.published
~~~

Để kiểm tra fallback, đọc post một lần, dừng Redis, chờ L1 post hết hạn rồi đọc lại:

~~~bash
docker compose stop redis
sleep 46
curl -i "http://localhost:8090/api/feed/posts/$POST_ID"
docker compose start redis
~~~

Request đọc phải trả HTTP 200 nếu Cassandra và Feed Service khỏe. feed.cache.redis.errors tăng sau lỗi Redis; log Feed chỉ ghi loại exception, không ghi Redis key. Chỉ làm thao tác này ở local.

## Kiểm thử tự động

Các unit test ở feed-service/src/test/java/com/devconnect/feed/cache/ kiểm tra L1 hit, L2 hit, serialization, exact eviction, Pub/Sub invalidation và fallback khi Redis lỗi.

RedisCacheIntegrationTests khởi động Redis 7.4 bằng Testcontainers rồi kiểm tra ghi, đọc và xóa exact key. Test bị bỏ qua khi Docker không khả dụng.

Cần JDK 21, Docker (cho integration test), và Maven hoặc Maven wrapper hợp lệ:

~~~bash
cd feed-service
./mvnw test
# hoặc nếu Maven được cài toàn cục:
mvn test

# chỉ integration test Redis
./mvnw -Dtest=RedisCacheIntegrationTests test
~~~

## Troubleshooting

| Hiện tượng | Kiểm tra và xử lý |
| --- | --- |
| redis-cli ping không trả PONG | Xem docker compose ps redis và docker compose logs redis, rồi khởi động lại Redis. |
| Không thấy key Redis | Xác nhận CACHE_ENABLED không phải false, endpoint đọc thành công và pattern đúng CACHE_ENVIRONMENT. |
| Key page biến mất sau khi tạo post | Đây là hành vi mong đợi: request mới dùng revision mới; key cũ hết hạn tự nhiên. |
| L1 của instance khác còn key cũ | Kiểm tra CACHE_INVALIDATION_CHANNEL và metric feed.cache.invalidation.received; invalidation chỉ xóa exact key ở L1 của từng instance. |
| feed.cache.redis.errors tăng | Redis, network hoặc timeout có vấn đề. Read vẫn fallback Cassandra; khắc phục hạ tầng Redis trước khi coi cache khỏe. |
| Testcontainers bị skip/fail | Docker daemon phải chạy và user phải có quyền Docker. Với Maven/JDK lỗi, cài JDK 21, cấu hình JAVA_HOME hoặc dùng Maven tương thích. |

## Giới hạn vận hành

Cache tối ưu tốc độ đọc, không thay backup hay tính nhất quán của Cassandra. Production cần Redis managed/cluster hoặc Sentinel, TLS, ACL, secret manager, timeout phù hợp và alert cho latency, memory/eviction và metric lỗi cache.
