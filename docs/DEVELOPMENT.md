# Phát triển và vận hành local

> Điều hướng: [Mục lục](README.md) · [Docker/Ubuntu](DOCKER.md) · [Database](DATABASE.md) · [API](API.md)

## 1. Chuẩn bị môi trường

Yêu cầu:

- JDK 21 và `JAVA_HOME` trỏ đúng JDK.
- Maven 3.9+ trong `PATH`, hoặc dùng wrapper của `feed-service`/`user-service`.
- Docker Engine/Desktop và Docker Compose v2 để chạy toàn bộ stack hoặc riêng infrastructure.

Kiểm tra:

```powershell
java -version
mvn -version
docker compose version
```

Parent POM quản lý bốn module, đặt `java.version=21` và import Spring Cloud BOM `2025.1.2`. Spring Boot parent hiện ở version 4.1.0; `feed-service` dùng `spring-cloud-starter-openfeign` cho internal HTTP call tới User Service.

## 2. Maven workflow

Từ repository root:

```powershell
# Compile toàn bộ
mvn compile

# Chạy test toàn bộ reactor
mvn test

# Build JAR, có chạy test
mvn clean package

# Chạy test một module và dependency cần thiết
mvn -pl feed-service -am test
```

Executable JAR sau build nằm tại:

```text
user-service/target/user-service-0.0.1-SNAPSHOT.jar
feed-service/target/feed-service-0.0.1-SNAPSHOT.jar
search-service/target/search-service-0.0.1-SNAPSHOT.jar
notification-service/target/notification-service-0.0.1-SNAPSHOT.jar
```

Chỉ `user-service` và `feed-service` có Maven Wrapper. Có thể dùng wrapper của Feed để chạy parent reactor từ root:

```powershell
.\feed-service\mvnw.cmd -f .\pom.xml test
```

Trên macOS/Linux:

```bash
./feed-service/mvnw -f ./pom.xml test
```

Wrapper có thể cần tải Maven ở lần đầu nên cần network và quyền ghi vào Maven user home.

## 3. Chạy toàn bộ stack bằng Docker Compose

Hướng dẫn Ubuntu/WSL, đồng bộ source, build cache và troubleshooting đầy đủ nằm trong [DOCKER.md](DOCKER.md). Luồng cơ bản:

```powershell
docker compose up -d --build
docker compose ps -a
docker compose logs -f user-service feed-service search-service notification-service
```

Compose khởi động:

- PostgreSQL 18.4 tại `localhost:5432`; named volume `postgres-data`.
- Cassandra 5.0.8 tại `localhost:9042`; named volume `cassandra-data`.
- One-shot container `cassandra-init` tạo keyspace `devconnect_feed`.
- Kafka và Kafka UI.
- Bốn Spring Boot application service được build từ Dockerfile riêng.

Compose tự chờ PostgreSQL/Cassandra/Kafka theo healthcheck, chờ `cassandra-init` hoàn tất và chờ User Service healthy trước khi khởi động Feed Service. Bốn application container cũng có TCP healthcheck. Cassandra thường cần lâu hơn các thành phần khác ở lần khởi động đầu.

Compose dùng Kafka ở KRaft mode một node, không cần ZooKeeper. Broker có hai advertised listener:

- `localhost:9092` cho application chạy trên host.
- `kafka:29092` cho container khác, ví dụ Kafka UI.

PostgreSQL dùng credential local `devconnect/devconnect`. Cassandra không bật authentication và keyspace dùng `NetworkTopologyStrategy` với replication factor 1 cho `datacenter1`. Các thiết lập này chỉ phù hợp local/demo.

Build lại một service sau khi sửa code:

```powershell
docker compose up -d --build feed-service
```

Xem log hoặc restart:

```powershell
docker compose logs -f feed-service
docker compose restart feed-service
```

Mỗi Dockerfile dùng Maven/JDK 21 ở build stage và Eclipse Temurin JRE 21 ở runtime stage. Runtime chạy bằng numeric non-root user `10001`; source, Maven cache và compiler không nằm trong image cuối.

## 4. Chạy trực tiếp trên host (tùy chọn)

Nếu cần debug trong IDE/Maven, chỉ khởi động infrastructure để tránh trùng port với application container:

```powershell
docker compose up -d postgres cassandra cassandra-init kafka kafka-ui
```

### Chạy bằng Maven

Mỗi lệnh cần một terminal riêng tại repository root:

```powershell
mvn -pl user-service spring-boot:run
mvn -pl feed-service spring-boot:run
mvn -pl search-service spring-boot:run
mvn -pl notification-service spring-boot:run
```

Nếu chạy application trực tiếp trên Ubuntu và port `8081` đang bận, chạy User Service ở `3000` và trỏ Feed Service tới port mới:

```bash
USER_SERVICE_PORT=3000 mvn -pl user-service spring-boot:run
USER_SERVICE_BASE_URL=http://localhost:3000 mvn -pl feed-service spring-boot:run
```

Hai lệnh phải chạy ở hai terminal riêng. Thay đổi này chỉ áp dụng cho process trên host; Compose đã publish sẵn `3000:8081`.

### Chạy bằng JAR

Sau `mvn clean package`:

```powershell
java -jar user-service\target\user-service-0.0.1-SNAPSHOT.jar
java -jar feed-service\target\feed-service-0.0.1-SNAPSHOT.jar
java -jar search-service\target\search-service-0.0.1-SNAPSHOT.jar
java -jar notification-service\target\notification-service-0.0.1-SNAPSHOT.jar
```

### Thứ tự khởi động khi chạy trên host

1. PostgreSQL, Cassandra/keyspace và Kafka.
2. User Service.
3. Feed Service.
4. Search Service và Notification Service.

User Service và Feed Service fail startup nếu database/keyspace chưa sẵn sàng. Consumer có thể log lỗi kết nối và tự thử lại nếu Kafka chưa sẵn sàng. Feed Service có thể khởi động khi User Service chưa chạy, nhưng create-post sẽ trả 503 cho tới khi dependency sẵn sàng.

## 5. Cấu hình

Tất cả cấu hình runtime hiện dùng profile mặc định.

| Environment variable | Service | Mặc định | Mục đích |
|---|---|---|---|
| `USER_SERVICE_PORT` | User | `8081` | HTTP port. |
| `FEED_SERVICE_PORT` | Feed | `8082` | HTTP port. |
| `SEARCH_SERVICE_PORT` | Search | `8083` | HTTP port. |
| `NOTIFICATION_SERVICE_PORT` | Notification | `8084` | HTTP port. |
| `POSTGRES_URL` | User | `jdbc:postgresql://localhost:5432/devconnect_users` | JDBC URL. |
| `POSTGRES_USERNAME` | User | `devconnect` | PostgreSQL user. |
| `POSTGRES_PASSWORD` | User | `devconnect` | PostgreSQL password. |
| `CASSANDRA_CONTACT_POINTS` | Feed | `localhost:9042` | Cassandra contact points. |
| `CASSANDRA_LOCAL_DATACENTER` | Feed | `datacenter1` | Local datacenter của driver. |
| `CASSANDRA_KEYSPACE` | Feed | `devconnect_feed` | Keyspace chứa post tables. |
| `CASSANDRA_REQUEST_TIMEOUT` | Feed | `5s` | Timeout cho CQL request. |
| `USER_SERVICE_BASE_URL` | Feed | `http://localhost:8081` | URL của OpenFeign named client `user-service`. |
| `KAFKA_BOOTSTRAP_SERVERS` | Feed/Search/Notification | `localhost:9092` | Danh sách Kafka bootstrap server. |
| `POST_ASYNC_CORE_POOL_SIZE` | Feed | `4` | Core worker cho create-post. |
| `POST_ASYNC_MAX_POOL_SIZE` | Feed | `16` | Worker tối đa. |
| `POST_ASYNC_QUEUE_CAPACITY` | Feed | `100` | Số task chờ tối đa. |
| `POST_ASYNC_AWAIT_TERMINATION_SECONDS` | Feed | `30` | Graceful shutdown wait. |

Ví dụ override trong PowerShell:

```powershell
$env:FEED_SERVICE_PORT = "9082"
$env:USER_SERVICE_BASE_URL = "http://localhost:9081"
$env:CASSANDRA_CONTACT_POINTS = "localhost:9042"
$env:KAFKA_BOOTSTRAP_SERVERS = "localhost:9092"
$env:POST_ASYNC_CORE_POOL_SIZE = "8"
mvn -pl feed-service spring-boot:run
```

Ví dụ Bash:

```bash
FEED_SERVICE_PORT=9082 \
USER_SERVICE_BASE_URL=http://localhost:9081 \
mvn -pl feed-service spring-boot:run
```

Nếu đổi User Service port, phải đổi đồng thời `USER_SERVICE_BASE_URL` của Feed Service. Nếu application chạy trong container cùng compose network, dùng hostname `postgres:5432`, `cassandra:9042` và Kafka `kafka:29092` thay cho `localhost`.

OpenFeign client `user-service` hiện cấu hình `connectTimeout=5000` và `readTimeout=5000` millisecond trong `feed-service/src/main/resources/application.yaml`. Hai timeout này chưa expose thành environment variable; project cũng chưa bật retry, circuit breaker hoặc service discovery.

Tên topic `post-events` và consumer group hiện chưa expose bằng environment variable.

Compose override các giá trị host mặc định bằng DNS name nội bộ:

| Service | Giá trị trong Compose |
|---|---|
| User | `POSTGRES_URL=jdbc:postgresql://postgres:5432/devconnect_users` |
| Feed | `CASSANDRA_CONTACT_POINTS=cassandra:9042` |
| Feed | `USER_SERVICE_BASE_URL=http://user-service:8081` |
| Feed/Search/Notification | `KAFKA_BOOTSTRAP_SERVERS=kafka:29092` |

Không đổi các giá trị này thành `localhost` bên trong container.

## 6. Test strategy hiện tại

| Test | Module | Phạm vi |
|---|---|---|
| `UserServiceApplicationTests` | User | Spring context load. |
| `UserServiceApplicationTests` | User | Hibernate tạo schema trên H2 PostgreSQL mode; create/update và email normalization. |
| `FeedServiceApplicationTests` | Feed | Spring context load và đăng ký `UserServiceClient` Feign proxy. |
| `CassandraPostStoreTests` | Feed | Logged batch, UUID lookup, timestamp mapping và feed ordering. |
| `UserServiceAdapterTests` | Feed | Validate response envelope và map Feign 404/downstream error. |
| `FeedServiceParallelValidationTests` | Feed | Hai validation chạy đồng thời, chỉ lưu post khi cả hai hợp lệ. |
| `FeedServiceUserContractTests` | Feed | Author `ACTIVE` được chấp nhận; author thiếu/inactive bị từ chối. |
| `FeedControllerTests` | Feed | Controller đồng bộ trả raw `PostResponse`/HTTP 201 và giữ error mapping. |
| `PostCreatedEventListenerTests` | Search | Initial partition replay đúng một lần và routing `POST_CREATED`. |
| `PostSearchServiceTests` | Search | Case-insensitive search và upsert theo `postId`. |
| `PostCreatedEventListenerTests` | Notification | Initial partition replay đúng một lần và routing `POST_CREATED`. |
| `NotificationServiceTests` | Notification | Deduplicate theo `eventId` và filter theo user. |

Test hiện tại không cần Docker: User Service dùng H2 ở PostgreSQL compatibility mode; Feed context mock `CqlSession`, Feign adapter/service/persistence behavior và consumer behavior dùng Mockito. Chưa có integration test với PostgreSQL/Cassandra/Kafka thật, Feign HTTP call thực giữa Feed/User hoặc producer callback.

Tại thời điểm tài liệu này được cập nhật, reactor có 50 test và tất cả đều pass từ clean build bằng `mvn clean test`.

Các test nên bổ sung:

- Unit test các content-validation edge case còn lại trong `FeedService`.
- MVC test cho mọi endpoint và validation edge case.
- OpenFeign contract/integration test với User Service thật hoặc WireMock/MockWebServer.
- PostgreSQL, Cassandra và Kafka integration test bằng Testcontainers.
- Contract test cho `PostCreatedEvent`.
- End-to-end test tạo post → search → notification với polling có timeout.
- Load test cho parallel validation executor và hành vi `CallerRunsPolicy`.

## 7. Smoke test thủ công

Các lệnh dưới đây giả định application đang chạy bằng Docker Compose, vì vậy User Service được truy cập qua host port `3000`. Khi chạy toàn bộ application trực tiếp trên host với cấu hình mặc định, User Service vẫn dùng `8081`.

```powershell
# User Service
Invoke-RestMethod "http://localhost:3000/internal/users/u001/status"

# Feed Service
$body = @{ authorId = "u001"; content = "DevConnect smoke test" } | ConvertTo-Json
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8082/api/feed/posts" `
  -ContentType "application/json" `
  -Body $body

Invoke-RestMethod "http://localhost:8082/api/feed/posts"

# Eventual-consistent consumers: có thể cần gọi lại sau một khoảng ngắn
Invoke-RestMethod "http://localhost:8083/api/search/posts?keyword=smoke"
Invoke-RestMethod "http://localhost:8084/api/notifications/users/u001"
```

Negative cases:

```powershell
# INACTIVE -> HTTP 400
$inactive = @{ authorId = "u003"; content = "Should fail" } | ConvertTo-Json
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8082/api/feed/posts" `
  -ContentType "application/json" `
  -Body $inactive

# Unknown user -> HTTP 400
$unknown = @{ authorId = "unknown"; content = "Should fail" } | ConvertTo-Json
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8082/api/feed/posts" `
  -ContentType "application/json" `
  -Body $unknown
```

## 8. Troubleshooting

### Port đã được sử dụng

Triệu chứng: startup fail với `Port ... was already in use`.

Kiểm tra trên Windows:

```powershell
Get-NetTCPConnection -LocalPort 3000,5432,8081,8082,8083,8084,8085,9042,9092 -ErrorAction SilentlyContinue
```

Dừng process xung đột hoặc override biến port tương ứng.

### Feed trả 503 `Failed to call User Service`

- Xác nhận User Service đang chạy.
- Nếu chạy bằng Compose, gọi trực tiếp `http://localhost:3000/internal/users/u001/status`.
- Nếu chạy application trên host với cấu hình mặc định, gọi `http://localhost:8081/internal/users/u001/status`.
- Kiểm tra `USER_SERVICE_BASE_URL`, nhất là khi đổi port hoặc chạy trong container; đây là URL của OpenFeign named client `user-service`.
- Mặc định Feign dừng chờ sau 5 giây cho connect/read timeout; timeout, lỗi decode và HTTP error khác 404 đều được map về cùng message 503 này.

### User Service không khởi động

- Kiểm tra `docker compose ps postgres` và `docker compose logs postgres`.
- Xác nhận `POSTGRES_URL`, username/password và database `devconnect_users`.
- Kiểm tra schema và dữ liệu user: `docker exec devconnect-postgres psql -U devconnect -d devconnect_users -c "SELECT user_id, status, email FROM users ORDER BY user_id;"`.

### Feed Service báo `Invalid keyspace devconnect_feed`

- Kiểm tra `docker compose ps -a cassandra-init`; container phải exit 0.
- Xem log bằng `docker compose logs cassandra cassandra-init`.
- Chạy lại one-shot init: `docker compose up cassandra-init`.
- Xác nhận `CASSANDRA_KEYSPACE` trùng với keyspace được tạo.

### Consumer liên tục không kết nối được Kafka

- Chạy `docker compose ps` và `docker compose logs kafka`.
- Với app trên host, dùng `localhost:9092`.
- Với app trong compose network, dùng `kafka:29092`.
- Chờ broker sẵn sàng hoàn toàn rồi quan sát consumer reconnect.

### Tạo post thành công nhưng search/notification rỗng

- Chờ eventual consistency và gọi lại API.
- Kiểm tra log publish trong Feed Service.
- Kiểm tra topic/message, consumer group và lag tại Kafka UI.
- Kiểm tra log replay `Rebuilding search index from the beginning of partitions [...]` và `Rebuilding notification read model from the beginning of partitions [...]`.
- Search/Notification mất map cục bộ khi restart nhưng tự replay event còn retention ở lần Kafka assignment đầu tiên. Gọi lại API sau khi consumer catch up; nếu event đã hết retention thì dữ liệu không thể được dựng lại bằng cơ chế demo này.

### Dữ liệu biến mất

PostgreSQL user và Cassandra post tồn tại qua application/container restart nhờ named volume. Search index, notification và notification deduplication mất map cục bộ khi service tương ứng restart nhưng được dựng lại từ event Kafka còn retention.

Muốn reset hoàn toàn database local:

```powershell
docker compose down -v
docker compose up -d
```

Lệnh `down -v` xóa dữ liệu PostgreSQL và Cassandra, chỉ nên dùng khi chủ động reset môi trường.

### Maven Wrapper không chạy được

- Dùng Maven đã cài sẵn: `mvn test`.
- Hoặc bảo đảm wrapper có network để tải distribution và có quyền ghi Maven user home/cache.
- Trên Windows dùng `mvnw.cmd`; trên macOS/Linux cấp execute permission cho `mvnw` nếu cần.

## 9. Kiểm tra database trực tiếp

PostgreSQL:

```powershell
docker exec devconnect-postgres `
  psql -U devconnect -d devconnect_users `
  -c "SELECT user_id, status FROM users ORDER BY user_id;"
```

Cassandra:

```powershell
docker exec devconnect-cassandra `
  cqlsh -e "SELECT post_id, author_id, created_at FROM devconnect_feed.posts_by_feed WHERE feed_id = 'global';"
```

Spring Data tự tạo hai Cassandra table nếu chưa tồn tại; keyspace được tạo bởi `cassandra-init`.

## 10. Quy trình thay đổi an toàn

Trước khi commit:

1. Chạy `mvn test` tại root.
2. Nếu đổi PostgreSQL schema, cập nhật `UserEntity` và kiểm tra tác động trên database hiện có; `ddl-auto=update` chỉ phù hợp local/demo và không thay thế quy trình migration production.
3. Nếu đổi Cassandra query, thiết kế table/read model tương ứng trước khi viết repository; tránh `ALLOW FILTERING`.
4. Nếu đổi event, cập nhật cả producer record và hai consumer record, rồi cập nhật `docs/EVENTS.md`.
5. Nếu đổi endpoint/config, cập nhật `docs/API.md` hoặc bảng biến môi trường.
6. Smoke test đường success và ít nhất một đường error.
7. Không commit `target/`, IDE metadata, log hoặc secret.

Repository chưa có CI configuration; nên đưa `mvn test` và build artifact vào pipeline khi tích hợp CI.
