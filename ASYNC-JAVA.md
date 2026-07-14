# Áp dụng Async Java trong DevConnect

## 1. Mục tiêu

Luồng tạo bài viết ban đầu chạy hoàn toàn trên thread HTTP:

```text
HTTP thread -> FeedService -> gọi đồng bộ User Service -> lưu post -> gửi Kafka -> trả response
```

`RestClient` là blocking I/O. Trong lúc chờ User Service, thread HTTP vẫn bị chiếm. Bản triển khai mới chuyển toàn bộ use case tạo post sang một executor riêng và trả `CompletableFuture` cho Spring MVC:

```text
HTTP thread -> AsyncPostService -> trả CompletableFuture cho Spring MVC
                         |
                         +-> post-async-* thread -> FeedService
                                                  -> User Service
                                                  -> lưu post
                                                  -> Kafka

Kafka -> Search Service (consumer group riêng)
      -> Notification Service (consumer group riêng)
```

Kết quả nghiệp vụ không đổi: API chỉ trả thành công sau khi đã xác minh tác giả và lưu post. Điểm thay đổi là servlet/request thread được trả về container trong lúc công việc chạy ở executor chuyên dụng.

## 2. Vì sao chọn `@Async` và `CompletableFuture`

- Project dùng Spring MVC và `RestClient`, tức là stack blocking. `@Async` phù hợp để tách blocking I/O khỏi request pool mà không cần đổi toàn bộ project sang WebFlux.
- `CompletableFuture` biểu diễn kết quả sẽ có trong tương lai. Spring MVC nhận kiểu trả về này, giữ HTTP connection bằng cơ chế async servlet và ghi response khi future hoàn tất.
- Executor riêng giúp tác vụ tạo post không dùng chung pool mặc định với các tác vụ nền khác.
- Pool và queue đều có giới hạn để tránh tạo thread không kiểm soát khi lưu lượng tăng cao.
- `KafkaTemplate.send()` đã bất đồng bộ và trả future, nên không cần bọc thêm một lớp `@Async`.

## 3. Các bước đã áp dụng

### Bước 1: Bật async và tạo executor riêng

File `feed-service/src/main/java/com/devconnect/feed/config/AsyncConfig.java`:

- `@EnableAsync` bật cơ chế proxy cho `@Async`.
- Bean có tên `postTaskExecutor` để code chỉ rõ pool cần sử dụng.
- `corePoolSize`: số worker thường trực.
- `maxPoolSize`: số worker tối đa khi queue đầy.
- `queueCapacity`: số task tối đa được chờ trong bộ nhớ.
- Prefix `post-async-` giúp nhận diện thread trong log và thread dump.
- `CallerRunsPolicy` tạo backpressure: khi pool và queue đều đầy, thread gửi task tự chạy task; request chậm lại thay vì task bị mất.
- Khi shutdown, service đợi task đang chạy hoàn thành tối đa theo cấu hình.

Thứ tự nhận task của `ThreadPoolTaskExecutor` là:

1. Tạo tối đa `core-pool-size` worker.
2. Khi các core worker bận, đưa task vào queue.
3. Khi queue đầy, tăng worker đến `max-pool-size`.
4. Khi cả pool và queue đầy, áp dụng `CallerRunsPolicy`.

### Bước 2: Tách async boundary sang bean riêng

File `feed-service/src/main/java/com/devconnect/feed/service/AsyncPostService.java` có:

```java
@Async("postTaskExecutor")
public CompletableFuture<PostResponse> createPost(CreatePostRequest request) {
    PostResponse post = feedService.createPost(request);
    return CompletableFuture.completedFuture(post);
}
```

Phải đặt method này ở bean riêng. Spring triển khai `@Async` qua proxy; gọi một method `@Async` từ method khác trong cùng object (self-invocation) sẽ đi thẳng vào object và không qua proxy, nên tác vụ vẫn chạy đồng bộ.

`FeedService` tiếp tục giữ logic nghiệp vụ đồng bộ. Cách tách này có ba lợi ích:

- Logic nghiệp vụ dễ đọc và dễ unit test.
- Async chỉ nằm ở boundary của use case.
- Không ép mọi caller của `FeedService.createPost()` phải chạy async.

### Bước 3: Cho controller trả `CompletableFuture`

File `feed-service/src/main/java/com/devconnect/feed/controller/FeedController.java` đổi endpoint POST thành:

```java
public CompletableFuture<ApiResponse<PostResponse>> createPost(...) {
    return asyncPostService.createPost(request)
            .thenApply(post -> ApiResponse.success("Post created successfully", post));
}
```

`thenApply` chỉ tạo `ApiResponse` sau khi post được tạo. Không gọi `join()` hoặc `get()` trong controller, vì hai lệnh đó sẽ block request thread và triệt tiêu lợi ích của async.

Validation `@Valid` vẫn chạy trước khi controller được gọi. Exception từ worker làm future hoàn tất ở trạng thái lỗi; Spring MVC dispatch lỗi trở lại pipeline xử lý exception hiện có.

### Bước 4: Đưa tham số pool ra cấu hình

File `feed-service/src/main/resources/application.yaml` thêm:

```yaml
app:
  async:
    post:
      core-pool-size: ${POST_ASYNC_CORE_POOL_SIZE:4}
      max-pool-size: ${POST_ASYNC_MAX_POOL_SIZE:16}
      queue-capacity: ${POST_ASYNC_QUEUE_CAPACITY:100}
      await-termination-seconds: ${POST_ASYNC_AWAIT_TERMINATION_SECONDS:30}
```

Có thể điều chỉnh khi deploy mà không sửa code. Ví dụ PowerShell:

```powershell
$env:POST_ASYNC_CORE_POOL_SIZE = "8"
$env:POST_ASYNC_MAX_POOL_SIZE = "32"
$env:POST_ASYNC_QUEUE_CAPACITY = "200"
```

Không nên tăng các số này tùy ý. Mỗi worker đang thực hiện blocking call đến User Service; pool quá lớn có thể chuyển điểm nghẽn sang User Service hoặc socket/connection của hệ thống.

### Bước 5: Kiểm thử đúng executor và đường lỗi

File `feed-service/src/test/java/com/devconnect/feed/service/AsyncPostServiceTests.java` kiểm tra:

- Logic thực sự chạy trên thread có prefix `post-async-`, khác thread gọi test.
- Kết quả từ worker được trả qua future.
- `BusinessException` làm future complete exceptionally, không bị nuốt mất.

File `feed-service/src/test/java/com/devconnect/feed/controller/FeedControllerAsyncTests.java` kiểm tra Spring MVC thực sự bắt đầu async request, trả response khi future hoàn tất và vẫn chuyển `BusinessException` tới `GlobalExceptionHandler` để trả HTTP 400.

## 4. Chạy project

Yêu cầu: JDK 21, Docker và Docker Compose.

Khởi động Kafka tại thư mục gốc:

```powershell
docker compose up -d
```

Mở bốn terminal và chạy:

```powershell
.\user-service\mvnw.cmd -f .\user-service\pom.xml spring-boot:run
.\feed-service\mvnw.cmd -f .\feed-service\pom.xml spring-boot:run
.\feed-service\mvnw.cmd -f .\search-service\pom.xml spring-boot:run
.\feed-service\mvnw.cmd -f .\notification-service\pom.xml spring-boot:run
```

Wrapper của `feed-service` có thể chạy các module còn lại thông qua `-f`.

## 5. Kiểm tra API

Tạo post với user đang hoạt động:

```powershell
$body = @{ authorId = "u001"; content = "Hoc Async Java" } | ConvertTo-Json
$post = Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8082/api/feed/posts `
  -ContentType "application/json" `
  -Body $body
$post
```

Sau khi Kafka consumer xử lý sự kiện, kiểm tra search và notification:

```powershell
Invoke-RestMethod "http://localhost:8083/api/search/posts?keyword=Async"
Invoke-RestMethod "http://localhost:8084/api/notifications/users/u001"
```

Kiểm tra đường lỗi với user không hoạt động:

```powershell
$body = @{ authorId = "u003"; content = "Khong duoc tao" } | ConvertTo-Json
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8082/api/feed/posts `
  -ContentType "application/json" `
  -Body $body
```

Kỳ vọng HTTP 400 với message `Author is not active`.

Chạy toàn bộ test từ thư mục gốc:

```powershell
.\feed-service\mvnw.cmd -f .\pom.xml test
```

## 6. Async đang tồn tại ở hai cấp khác nhau

### Async trong cùng một JVM

`@Async` + `CompletableFuture` chạy công việc trên executor của `feed-service`. Nó giúp quản lý thread và thời gian chờ HTTP, nhưng task không bền vững: nếu process chết giữa chừng, task trong memory mất theo.

### Async giữa các microservice

Kafka tách việc tạo post khỏi index search và tạo notification. Event nằm ở broker, các consumer group xử lý độc lập. Đây là lựa chọn phù hợp cho side effect có thể eventual-consistent.

Không nên chuyển bước kiểm tra tác giả sang kiểu fire-and-forget, vì `feed-service` phải biết user có `ACTIVE` trước khi chấp nhận post.

## 7. Lưu ý production

- Dữ liệu demo đang nằm trong `ConcurrentHashMap`; restart service sẽ mất dữ liệu. Khi dùng database, nên cân nhắc Transactional Outbox để tránh trường hợp lưu post thành công nhưng gửi Kafka thất bại.
- Đặt timeout cho lời gọi User Service. Async không tự tạo timeout; nó chỉ chuyển nơi chờ sang pool khác.
- Theo dõi active thread, queue size, thời gian thực thi và số lần reject bằng Micrometer/Actuator.
- Truyền correlation ID/MDC sang worker nếu hệ thống dùng distributed tracing.
- Các Kafka consumer nên cấu hình retry và dead-letter topic cho lỗi không phục hồi được.
- Chọn kích thước pool bằng load test. Khởi điểm có thể ước lượng theo Little's Law: concurrency xấp xỉ throughput mục tiêu nhân thời gian chờ trung bình, rồi giới hạn theo khả năng chịu tải của User Service.
