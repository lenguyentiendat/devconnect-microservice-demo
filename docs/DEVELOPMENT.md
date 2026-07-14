# Phát triển và vận hành local

## 1. Chuẩn bị môi trường

Yêu cầu:

- JDK 21 và `JAVA_HOME` trỏ đúng JDK.
- Maven 3.9+ trong `PATH`, hoặc dùng wrapper của `feed-service`/`user-service`.
- Docker Engine/Desktop và Docker Compose v2 để chạy Kafka.

Kiểm tra:

```powershell
java -version
mvn -version
docker compose version
```

Parent POM quản lý bốn module và đặt `java.version=21`. Spring Boot parent hiện ở version 4.1.0.

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

## 3. Hạ tầng Kafka

```powershell
docker compose up -d
docker compose ps
docker compose logs -f kafka
```

Compose dùng Kafka ở KRaft mode một node, không cần ZooKeeper. Broker có hai advertised listener:

- `localhost:9092` cho application chạy trên host.
- `kafka:29092` cho container khác, ví dụ Kafka UI.

Các thiết lập replication factor bằng 1 chỉ phù hợp local/demo.

## 4. Chạy application

### Chạy bằng Maven

Mỗi lệnh cần một terminal riêng tại repository root:

```powershell
mvn -pl user-service spring-boot:run
mvn -pl feed-service spring-boot:run
mvn -pl search-service spring-boot:run
mvn -pl notification-service spring-boot:run
```

### Chạy bằng JAR

Sau `mvn clean package`:

```powershell
java -jar user-service\target\user-service-0.0.1-SNAPSHOT.jar
java -jar feed-service\target\feed-service-0.0.1-SNAPSHOT.jar
java -jar search-service\target\search-service-0.0.1-SNAPSHOT.jar
java -jar notification-service\target\notification-service-0.0.1-SNAPSHOT.jar
```

### Thứ tự khởi động

1. Kafka.
2. User Service.
3. Feed Service.
4. Search Service và Notification Service.

Consumer có thể log lỗi kết nối và tự thử lại nếu Kafka chưa sẵn sàng. Feed Service có thể khởi động khi User Service chưa chạy, nhưng create-post sẽ trả 503 cho tới khi dependency sẵn sàng.

## 5. Cấu hình

Tất cả cấu hình runtime hiện dùng profile mặc định.

| Environment variable | Service | Mặc định | Mục đích |
|---|---|---|---|
| `USER_SERVICE_PORT` | User | `8081` | HTTP port. |
| `FEED_SERVICE_PORT` | Feed | `8082` | HTTP port. |
| `SEARCH_SERVICE_PORT` | Search | `8083` | HTTP port. |
| `NOTIFICATION_SERVICE_PORT` | Notification | `8084` | HTTP port. |
| `USER_SERVICE_BASE_URL` | Feed | `http://localhost:8081` | Base URL gọi User Service. |
| `KAFKA_BOOTSTRAP_SERVERS` | Feed/Search/Notification | `localhost:9092` | Danh sách Kafka bootstrap server. |
| `POST_ASYNC_CORE_POOL_SIZE` | Feed | `4` | Core worker cho create-post. |
| `POST_ASYNC_MAX_POOL_SIZE` | Feed | `16` | Worker tối đa. |
| `POST_ASYNC_QUEUE_CAPACITY` | Feed | `100` | Số task chờ tối đa. |
| `POST_ASYNC_AWAIT_TERMINATION_SECONDS` | Feed | `30` | Graceful shutdown wait. |

Ví dụ override trong PowerShell:

```powershell
$env:FEED_SERVICE_PORT = "9082"
$env:USER_SERVICE_BASE_URL = "http://localhost:9081"
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

Nếu đổi User Service port, phải đổi đồng thời `USER_SERVICE_BASE_URL` của Feed Service. Nếu application chạy trong container cùng compose network, Kafka bootstrap server phải là `kafka:29092`, không phải `localhost:9092`.

Tên topic `post-events` và consumer group hiện chưa expose bằng environment variable.

## 6. Test strategy hiện tại

| Test | Module | Phạm vi |
|---|---|---|
| `UserServiceApplicationTests` | User | Spring context load. |
| `FeedServiceApplicationTests` | Feed | Spring context load. |
| `AsyncPostServiceTests` | Feed | Chạy đúng executor, future result và business exception. |
| `FeedControllerAsyncTests` | Feed | Async MVC dispatch, success body và HTTP 400 mapping. |

Test hiện tại không cần Kafka/Docker vì không có integration test gửi/consume message thật. Chưa có test cho REST call thực giữa Feed/User, producer callback, search consumer và notification deduplication.

Các test nên bổ sung:

- Unit test toàn bộ business branch trong `FeedService`.
- MVC test cho mọi endpoint và validation edge case.
- Kafka listener/serialization test bằng embedded Kafka hoặc Testcontainers.
- Contract test cho `PostCreatedEvent`.
- End-to-end test tạo post → search → notification với polling có timeout.
- Load test cho async executor và hành vi `CallerRunsPolicy`.

## 7. Smoke test thủ công

```powershell
# User Service
Invoke-RestMethod "http://localhost:8081/internal/users/u001/status"

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
Get-NetTCPConnection -LocalPort 8081,8082,8083,8084,8085,9092 -ErrorAction SilentlyContinue
```

Dừng process xung đột hoặc override biến port tương ứng.

### Feed trả 503 `Failed to call User Service`

- Xác nhận User Service đang chạy.
- Gọi trực tiếp `http://localhost:8081/internal/users/u001/status`.
- Kiểm tra `USER_SERVICE_BASE_URL`, nhất là khi đổi port hoặc chạy trong container.

### Consumer liên tục không kết nối được Kafka

- Chạy `docker compose ps` và `docker compose logs kafka`.
- Với app trên host, dùng `localhost:9092`.
- Với app trong compose network, dùng `kafka:29092`.
- Chờ broker sẵn sàng hoàn toàn rồi quan sát consumer reconnect.

### Tạo post thành công nhưng search/notification rỗng

- Chờ eventual consistency và gọi lại API.
- Kiểm tra log publish trong Feed Service.
- Kiểm tra topic/message, consumer group và lag tại Kafka UI.
- Nhớ rằng restart Search/Notification làm mất read model trong memory; consumer có thể tiếp tục từ offset đã commit nên dữ liệu cũ không tự xuất hiện lại.

### Dữ liệu biến mất

Đây là hành vi dự kiến của demo. Mỗi service lưu business data trong memory; restart process sẽ xóa dữ liệu tương ứng.

### Maven Wrapper không chạy được

- Dùng Maven đã cài sẵn: `mvn test`.
- Hoặc bảo đảm wrapper có network để tải distribution và có quyền ghi Maven user home/cache.
- Trên Windows dùng `mvnw.cmd`; trên macOS/Linux cấp execute permission cho `mvnw` nếu cần.

## 9. Quy trình thay đổi an toàn

Trước khi commit:

1. Chạy `mvn test` tại root.
2. Nếu đổi event, cập nhật cả producer record và hai consumer record, rồi cập nhật `docs/EVENTS.md`.
3. Nếu đổi endpoint/config, cập nhật `docs/API.md` hoặc bảng biến môi trường.
4. Smoke test đường success và ít nhất một đường error.
5. Không commit `target/`, IDE metadata, log hoặc secret.

Repository chưa có CI configuration; nên đưa `mvn test` và build artifact vào pipeline khi tích hợp CI.
