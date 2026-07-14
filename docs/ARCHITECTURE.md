# Kiến trúc hệ thống

## 1. Mục tiêu và phạm vi

DevConnect minh họa một use case nhỏ nhưng có đủ hai kiểu giao tiếp thường gặp trong microservice:

- Giao tiếp đồng bộ khi kết quả của service khác là điều kiện để chấp nhận request.
- Giao tiếp bất đồng bộ khi các side effect có thể hoàn thành sau response và chấp nhận eventual consistency.

Use case trung tâm là tạo post. Tác giả phải tồn tại và có trạng thái `ACTIVE`; việc lập chỉ mục search và tạo notification không cần hoàn tất trước khi client nhận response.

## 2. Phân rã service

### `user-service`

Sở hữu thông tin trạng thái user và chỉ cung cấp endpoint nội bộ `GET /internal/users/{userId}/status`.

Dữ liệu demo được hard-code bằng `Map.of`:

- `u001`: `ACTIVE`
- `u002`: `ACTIVE`
- `u003`: `INACTIVE`

Service không có API tạo/cập nhật user và không dùng database.

### `feed-service`

Là service điều phối luồng tạo post:

- Validate `authorId` và `content` không rỗng.
- Gọi `user-service` bằng Spring `RestClient`.
- Áp dụng rule tác giả phải `ACTIVE`.
- Lưu post vào `ConcurrentHashMap`.
- Phát `POST_CREATED` bằng `KafkaTemplate`.
- Cung cấp API lấy danh sách và chi tiết post.

Đây là service duy nhất có exception response chuẩn hóa cho validation, business error, downstream error và unexpected error.

### `search-service`

Consume `POST_CREATED`, upsert post vào index trong memory theo `postId`, và tìm kiếm không phân biệt hoa thường bằng phép `String.contains` trên nội dung.

Đây không phải full-text search engine: không có tokenization, ranking, stemming, pagination hay persistent index.

### `notification-service`

Consume `POST_CREATED` và tạo một notification cho chính tác giả. Service lưu thêm `processedEventIds` để bỏ qua event trùng theo `eventId` trong vòng đời hiện tại của process.

Idempotency này cũng chỉ nằm trong memory, nên không còn hiệu lực sau restart.

## 3. Luồng tạo post

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant FC as FeedController
    participant AP as AsyncPostService
    participant FS as FeedService
    participant US as user-service
    participant K as Kafka
    participant SS as search-service
    participant NS as notification-service

    C->>FC: POST /api/feed/posts
    FC->>AP: createPost(request)
    AP-->>FC: CompletableFuture
    Note over FC: Servlet request được chuyển sang async mode
    AP->>FS: createPost(request) trên post-async-* thread
    FS->>US: GET /internal/users/{id}/status
    US-->>FS: ApiResponse<UserStatusResponse>
    alt User ACTIVE
        FS->>FS: Lưu post trong memory
        FS->>K: send POST_CREATED (key = postId)
        FS-->>AP: PostResponse
        AP-->>FC: Complete future
        FC-->>C: HTTP 200
        K-->>SS: POST_CREATED
        SS->>SS: Upsert search index
        K-->>NS: POST_CREATED
        NS->>NS: Deduplicate và tạo notification
    else User không tồn tại/INACTIVE/downstream lỗi
        FS-->>FC: Exception qua failed future
        FC-->>C: HTTP 400/503/500
    end
```

### Async bên trong `feed-service`

`AsyncPostService.createPost()` được annotate `@Async("postTaskExecutor")` và trả `CompletableFuture`. Spring MVC giữ HTTP connection bằng async servlet processing, còn blocking call đến `user-service` chạy trên pool `post-async-*`.

Executor mặc định:

| Thuộc tính | Giá trị | Ý nghĩa |
|---|---:|---|
| Core pool size | 4 | Số worker duy trì bình thường. |
| Max pool size | 16 | Số worker tối đa khi queue đầy. |
| Queue capacity | 100 | Số task chờ tối đa trong memory. |
| Await termination | 30 giây | Thời gian chờ task khi shutdown. |
| Rejection policy | `CallerRunsPolicy` | Request thread tự chạy task khi pool và queue đều đầy, tạo backpressure. |

Async này không biến `RestClient` thành non-blocking I/O; nó chỉ chuyển nơi chờ sang một executor được giới hạn riêng. Xem [Async Java trong DevConnect](../ASYNC-JAVA.md) để hiểu chi tiết.

### Async giữa các service

Kafka tách response tạo post khỏi search và notification. Hai consumer dùng group ID khác nhau, do đó mỗi group đều nhận một bản của event:

```text
post-events
├── search-service-group       -> cập nhật search index
└── notification-service-group -> tạo notification
```

Trong cùng một group, Kafka phân phối mỗi partition cho một consumer instance tại một thời điểm. Producer dùng `postId` làm message key, nên các event cùng post sẽ ổn định trên cùng partition khi số partition không đổi.

## 4. Dữ liệu và quyền sở hữu

| Dữ liệu | Service sở hữu | Storage hiện tại | Mất khi restart |
|---|---|---|---|
| User status | `user-service` | Immutable in-memory map | Trở về seed data |
| Post | `feed-service` | `ConcurrentHashMap<postId, post>` | Có |
| Search index | `search-service` | `ConcurrentHashMap<postId, post>` | Có |
| Notification | `notification-service` | `ConcurrentHashMap<notificationId, notification>` | Có |
| Processed event IDs | `notification-service` | `ConcurrentHashMap<eventId, boolean>` | Có |

Không service nào đọc trực tiếp storage của service khác. `feed-service` lấy trạng thái user qua API; search và notification xây read model riêng từ event.

## 5. Consistency và failure semantics

### Tạo post và kiểm tra user

Đây là nhánh strongly ordered trong phạm vi request: post chỉ được tạo sau khi `user-service` trả về user `ACTIVE`. Nếu user không tồn tại hoặc inactive, map lỗi về HTTP 400. Nếu không kết nối được User Service hoặc service trả 5xx, map về HTTP 503.

### Lưu post và publish event

Hai thao tác không nằm trong cùng transaction:

1. Post được đưa vào map.
2. `KafkaTemplate.send()` được gọi bất đồng bộ.
3. API trả thành công mà không chờ broker acknowledge.

Nếu broker từ chối message hoặc lỗi gửi xảy ra sau đó, lỗi chỉ được log; post vẫn tồn tại nhưng search/notification có thể không được cập nhật. Đây là chủ đích của demo, không phải đảm bảo phù hợp production.

### Consumer delivery

Kafka thường được vận hành với at-least-once processing. Code hiện tại phản ứng với duplicate như sau:

- Search upsert theo `postId`, nên xử lý lại cùng dữ liệu không tạo thêm bản ghi.
- Notification deduplicate theo `eventId`, nhưng trạng thái dedup không persistent.

Chưa có retry/backoff tùy chỉnh, dead-letter topic hoặc handler cho poison message.

## 6. Error model

```mermaid
flowchart TD
    R[Create post request] --> V{Body hợp lệ?}
    V -- Không --> E400V[400: validation message]
    V -- Có --> U{User Service response}
    U -- 404 / invalid body --> E400N[400: Author not found]
    U -- User INACTIVE --> E400I[400: Author is not active]
    U -- 5xx / connection error --> E503[503: downstream error]
    U -- ACTIVE --> P[Lưu post và bắt đầu publish]
    P --> OK[200: Post created successfully]
    R -. unexpected exception .-> E500[500: Internal server error]
```

Chi tiết body và status code nằm trong [REST API reference](API.md).

## 7. Khả năng scale

- Các map local không được chia sẻ giữa nhiều instance. Scale ngang `feed-service` sẽ làm mỗi instance có tập post khác nhau; API GET cho kết quả không nhất quán.
- Scale consumer service tạo read model riêng trên từng instance. Với cùng consumer group, mỗi instance chỉ nhận một phần partition nhưng lại phục vụ request trên local memory, nên mỗi instance không có index đầy đủ.
- Không có load balancer, gateway hay discovery trong repository.
- `postTaskExecutor` giới hạn concurrency nhưng kích thước pool cần được đo bằng load test và giới hạn theo sức chịu tải của `user-service`.

Vì vậy cấu trúc hiện tại chỉ nên chạy một instance cho mỗi service.

## 8. Khả năng sẵn sàng cho production

Các nâng cấp quan trọng, theo thứ tự ưu tiên hợp lý:

1. Thay in-memory map bằng database thuộc quyền sở hữu từng service.
2. Dùng Transactional Outbox ở `feed-service` để commit post và outbox record trong cùng transaction; relay outbox sang Kafka.
3. Persistent idempotency/inbox cho consumer, kèm retry có backoff và dead-letter topic.
4. Định nghĩa schema event có version; dùng schema registry hoặc contract test để kiểm soát compatibility.
5. Cấu hình connect/read timeout, retry có giới hạn và circuit breaker cho HTTP call tới `user-service`.
6. Thêm authentication/authorization; không public endpoint `/internal/**` ra ngoài gateway.
7. Thêm Actuator health/readiness, Micrometer metrics, structured logging, correlation ID và distributed tracing.
8. Bổ sung integration test với Kafka thật/Testcontainers và end-to-end test.
9. Pin tất cả container image version và bổ sung Dockerfile/deployment manifest cho application service.
10. Thiết kế pagination, deterministic ordering và API versioning trước khi có dữ liệu lớn.

## 9. Quy ước package

Mỗi module dùng package gốc `com.devconnect.<service>` và chia theo vai trò:

- `controller`: REST adapter.
- `service`: business/application logic.
- `dto`: request/response model.
- `config`: Spring bean và infrastructure config.
- `event`: Kafka contract, producer hoặc listener.
- `exception`: domain/downstream exception và HTTP mapping.

Project cố ý giữ event record riêng trong từng module để các service không phụ thuộc compile-time vào một shared Java library. Đổi event contract vì thế phải được đồng bộ giữa producer và consumer, hoặc cần một cơ chế schema/version tốt hơn.
