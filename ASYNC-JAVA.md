# Parallel và Async Java trong DevConnect

> Điều hướng: [Mục lục documentation](docs/README.md) · [Kiến trúc](docs/ARCHITECTURE.md) · [Development](docs/DEVELOPMENT.md)

## 1. Mục tiêu

Luồng tạo post phải đợi các điều kiện bắt buộc trước khi trả thành công:

- Tác giả tồn tại và có trạng thái `ACTIVE`.
- Nội dung post hợp lệ.
- Logged batch Cassandra đã hoàn tất.

Kiểm tra tác giả và kiểm tra nội dung chỉ phụ thuộc request, không phụ thuộc kết quả của nhau. `FeedService` chạy hai tác vụ này song song để thời gian validation gần với tác vụ lâu nhất thay vì tổng thời gian của cả hai.

```text
                              +-> OpenFeign: lấy trạng thái tác giả --+
HTTP request -> FeedService --|                                      |-> thenCombine -> validate -> lưu post
                              +-> kiểm tra nội dung ------------------+
```

Controller vẫn là synchronous HTTP boundary:

```java
@PostMapping
public ResponseEntity<PostResponse> createPost(
        @Valid @RequestBody CreatePostRequest request
) {
    PostResponse response = feedService.createPost(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
}
```

API trả `201 Created` với raw `PostResponse`. Controller không trả hoặc expose `CompletableFuture`.

## 2. Parallel không đồng nghĩa non-blocking

Hai khái niệm cần tách biệt:

- **Parallel trong một request:** hai tác vụ độc lập chạy cùng lúc trên hai worker thread để giảm latency.
- **Non-blocking HTTP:** request thread không bị giữ trong lúc chờ I/O.

Implementation hiện tại chỉ đạt mục tiêu thứ nhất. `FeedService.createPost()` gọi `.join()`, vì vậy HTTP request thread vẫn chờ tới khi hai future hoàn thành. OpenFeign cũng là blocking I/O. Cách làm này không tăng khả năng phục vụ đồng thời giống WebFlux/non-blocking client; lợi ích chính là giảm thời gian chờ khi hai validation đều đủ tốn thời gian và thực sự độc lập.

Ví dụ:

```text
Tuần tự:  author status 700 ms + content validation 500 ms ≈ 1200 ms
Song song: max(700 ms, 500 ms)                         ≈ 700 ms
```

Content validation của demo chỉ chạy local và rất nhanh, nên mức cải thiện thực tế có thể nhỏ. Lợi ích rõ hơn nếu bước này gọi Content Moderation Service hoặc thực hiện một tác vụ độc lập có latency đáng kể.

## 3. Cách `FeedService` chạy hai tác vụ

Hai future phải được tạo trước khi chờ:

```java
CompletableFuture<UserStatusResponse> authorStatusFuture =
        CompletableFuture.supplyAsync(
                () -> getAuthorStatus(request.authorId()),
                postTaskExecutor
        );

CompletableFuture<ContentValidationResponse> contentValidationFuture =
        CompletableFuture.supplyAsync(
                () -> validatePostContent(request.content()),
                postTaskExecutor
        );

PostCreationValidation validation = authorStatusFuture
        .thenCombine(
                contentValidationFuture,
                PostCreationValidation::new
        )
        .join();
```

`thenCombine()` chỉ tạo `PostCreationValidation` khi cả hai future hoàn thành thành công. Sau đó service kiểm tra kết quả và chỉ gọi `createAndSavePost()` khi tác giả lẫn nội dung đều hợp lệ.

Không viết theo cách join future thứ nhất trước khi tạo future thứ hai:

```java
UserStatusResponse author = CompletableFuture
        .supplyAsync(() -> getAuthorStatus(authorId), postTaskExecutor)
        .join();

ContentValidationResponse content = CompletableFuture
        .supplyAsync(() -> validatePostContent(value), postTaskExecutor)
        .join();
```

Cách trên vẫn tuần tự vì task thứ hai chỉ được submit sau khi task thứ nhất hoàn tất.

## 4. Executor

`AsyncConfig` cung cấp bean `postTaskExecutor` bằng `ThreadPoolTaskExecutor`:

| Thuộc tính | Mặc định | Ý nghĩa |
|---|---:|---|
| Core pool size | 4 | Số worker duy trì bình thường. |
| Max pool size | 16 | Số worker tối đa khi queue đầy. |
| Queue capacity | 100 | Số task chờ tối đa trong memory. |
| Await termination | 30 giây | Thời gian chờ task khi shutdown. |
| Thread prefix | `post-async-` | Nhận diện worker trong log/thread dump. |
| Rejection policy | `CallerRunsPolicy` | Caller tự chạy task khi pool và queue đầy để tạo backpressure. |

Hai task chỉ có thể chạy đồng thời nếu executor có ít nhất hai worker sẵn sàng. `@EnableAsync` vẫn có trên configuration, nhưng parallelism của use case hiện tại đến từ `CompletableFuture.supplyAsync(..., postTaskExecutor)`, không phụ thuộc một method `@Async` hay Spring proxy.

Runtime config:

```yaml
app:
  async:
    post:
      core-pool-size: ${POST_ASYNC_CORE_POOL_SIZE:4}
      max-pool-size: ${POST_ASYNC_MAX_POOL_SIZE:16}
      queue-capacity: ${POST_ASYNC_QUEUE_CAPACITY:100}
      await-termination-seconds: ${POST_ASYNC_AWAIT_TERMINATION_SECONDS:30}
```

Không tăng pool tùy ý. Nhiều worker hơn có thể chuyển điểm nghẽn sang User Service, Cassandra hoặc tài nguyên socket/CPU của Feed Service.

## 5. OpenFeign boundary

`UserServiceClient` khai báo HTTP contract:

```java
@FeignClient(name = "user-service")
public interface UserServiceClient {

    @GetMapping("/internal/users/{userId}/status")
    ApiResponse<UserStatusResponse> getUserStatus(
            @PathVariable("userId") String userId
    );
}
```

`UserServiceAdapter` bọc Feign client để `FeedService` không phụ thuộc vào `FeignException`:

- Feign `404` hoặc envelope null/không thành công/không có data → `BusinessException("Author not found")` → HTTP 400.
- Feign HTTP error khác, lỗi kết nối, timeout hoặc decoding → `DownstreamServiceException("Failed to call User Service")` → HTTP 503.

Adapter này không phải API Gateway. Nó là outbound adapter bên trong Feed Service. API Gateway trong tương lai là edge component nhận request từ client và route tới các service.

Named client `user-service` dùng URL từ `USER_SERVICE_BASE_URL`; connect timeout và read timeout đều là 5000 ms:

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          user-service:
            url: ${USER_SERVICE_BASE_URL:http://localhost:8081}
            connectTimeout: 5000
            readTimeout: 5000
```

## 6. Exception từ `CompletableFuture`

Nếu task ném exception, `.join()` bọc nguyên nhân trong `CompletionException`. `FeedService` unwrap để giữ đúng business/downstream error:

```java
private RuntimeException unwrapCompletionException(
        CompletionException exception
) {
    Throwable cause = exception.getCause();

    if (cause instanceof RuntimeException runtimeException) {
        return runtimeException;
    }

    return new IllegalStateException("Post creation failed", cause);
}
```

Nhờ vậy `GlobalExceptionHandler` vẫn map `BusinessException` thành HTTP 400 và `DownstreamServiceException` thành HTTP 503.

## 7. Async giữa các microservice

Sau khi validation và Cassandra write thành công, Feed Service gọi `KafkaTemplate.send()` để publish `POST_CREATED`. Việc publish là fire-and-observe: API không chờ broker acknowledge trước khi trả HTTP 201.

```text
Feed Service -> Kafka topic post-events
                    +-> search-service-group       -> search index
                    +-> notification-service-group -> notification
```

Đây mới là asynchronous boundary giữa các service. Search và notification chấp nhận eventual consistency; chúng có thể hoàn thành sau response create-post. Ngược lại, kiểm tra tác giả không thể fire-and-forget vì kết quả là điều kiện để chấp nhận post.

## 8. Kiểm thử

Các test liên quan hiện tại:

- `FeedServiceParallelValidationTests`: chứng minh hai task có thể chạy trên hai worker khác nhau và post không được lưu khi content bị từ chối.
- `FeedServiceUserContractTests`: kiểm tra author `ACTIVE`, inactive và không tồn tại.
- `UserServiceAdapterTests`: kiểm tra response envelope, Feign 404 và downstream exception mapping.
- `FeedControllerTests`: kiểm tra synchronous controller trả HTTP 201/raw `PostResponse` và chuyển business exception tới handler.
- `FeedServiceApplicationTests`: xác nhận Spring context tạo được Feign proxy `UserServiceClient`.

Chạy riêng Feed Service:

```powershell
mvn -pl feed-service test
```

Log mong đợi khi hai task chạy song song có hai thread khác nhau:

```text
Getting author status on thread: post-async-1
Validating content on thread: post-async-2
```

## 9. Lưu ý production

- Đo latency thực tế trước khi duy trì parallel local validation; task quá nhỏ có thể không bù được chi phí scheduling.
- Tách executor theo loại workload nếu content moderation sau này dùng CPU nặng, tránh dùng chung pool với blocking HTTP I/O.
- Bổ sung circuit breaker, retry có giới hạn và metrics cho OpenFeign; tránh retry mù quáng làm tăng tải User Service.
- Theo dõi active thread, queue size, reject count, Feign latency và timeout bằng Actuator/Micrometer.
- Truyền correlation ID/MDC sang worker để giữ distributed tracing xuyên qua `CompletableFuture`.
- Dùng outbox/CDC nếu cần bảo đảm post đã lưu luôn có event tương ứng; hiện lỗi Kafka sau Cassandra write không rollback post.
- Nếu mục tiêu là giải phóng request thread trong lúc chờ I/O, cân nhắc Spring MVC async return type/`DeferredResult` hoặc WebFlux end-to-end. Đó là thay đổi kiến trúc khác với parallelism hiện tại.
