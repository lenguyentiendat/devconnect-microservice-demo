# REST API reference

> Điều hướng: [Mục lục](README.md) · [Quick start](../README.md) · [Kiến trúc](ARCHITECTURE.md) · [Events](EVENTS.md)

## 1. Quy ước chung

Base URL local phụ thuộc service:

| Service | Base URL |
|---|---|
| User | `http://localhost:3000` |
| Feed | `http://localhost:8082` |
| Search | `http://localhost:8083` |
| Notification | `http://localhost:8084` |

Business response dùng envelope:

```json
{
  "success": true,
  "message": "Mô tả kết quả",
  "data": {}
}
```

Error do `feed-service` và `user-service` chủ động tạo có dạng:

```json
{
  "success": false,
  "message": "Mô tả lỗi",
  "data": null
}
```

Tất cả timestamp API là `LocalDateTime` được Jackson serialize theo ISO-8601 nhưng không chứa timezone/UTC offset. Feed Service tạo thời gian theo UTC, truncate về millisecond để khớp Cassandra `timestamp`, ví dụ `2026-07-14T03:15:30.123`.

Project chưa version API, chưa có authentication, pagination hay OpenAPI endpoint.

### Danh sách endpoint

| Method | Path | Service | Success |
|---|---|---|---:|
| GET | `/internal/users/{userId}/status` | User | 200 |
| POST | `/api/feed/posts` | Feed | 200 |
| GET | `/api/feed/posts` | Feed | 200 |
| GET | `/api/feed/posts/{postId}` | Feed | 200 |
| GET | `/api/search/posts?keyword=...` | Search | 200 |
| GET | `/api/notifications/users/{userId}` | Notification | 200 |

Không endpoint nào yêu cầu token trong bản demo. `/internal/**` chỉ mang ý nghĩa service-to-service theo convention; nó vẫn có thể truy cập từ host qua port `3000`. Bên trong Compose network, User Service vẫn lắng nghe port `8081`.

## 2. User Service

### Lấy trạng thái user

```http
GET /internal/users/{userId}/status
```

Endpoint này được thiết kế cho service-to-service call, dù demo chưa có cơ chế chặn public access.

Ví dụ:

```powershell
Invoke-RestMethod "http://localhost:3000/internal/users/u001/status"
```

Response `200 OK`:

```json
{
  "success": true,
  "message": "User status found",
  "data": {
    "userId": "u001",
    "status": "ACTIVE"
  }
}
```

Response `404 Not Found` khi ID không tồn tại:

```json
{
  "success": false,
  "message": "User not found",
  "data": null
}
```

## 3. Feed Service

### Tạo post

```http
POST /api/feed/posts
Content-Type: application/json
```

Request body:

| Field | Kiểu | Bắt buộc | Rule |
|---|---|---|---|
| `authorId` | string | Có | Không được null, rỗng hoặc chỉ có whitespace. |
| `content` | string | Có | Không được null, rỗng hoặc chỉ có whitespace. |

Ví dụ:

```powershell
$body = @{ authorId = "u001"; content = "Spring Boot and Kafka" } | ConvertTo-Json
Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8082/api/feed/posts" `
  -ContentType "application/json" `
  -Body $body
```

Response `200 OK`:

```json
{
  "success": true,
  "message": "Post created successfully",
  "data": {
    "postId": "5b990c7c-72b1-4af3-9f50-66c56d9ee94d",
    "authorId": "u001",
    "content": "Spring Boot and Kafka",
    "createdAt": "2026-07-14T03:15:30.123"
  }
}
```

Lưu ý: API hiện trả `200`, không trả `201 Created`, và không có header `Location`.

Các lỗi có thể có:

| HTTP | Message | Điều kiện |
|---:|---|---|
| 400 | `authorId is required` | `authorId` null/rỗng/blank. |
| 400 | `content is required` | `content` null/rỗng/blank. |
| 400 | `Author not found` | User Service trả 4xx, body không hợp lệ hoặc user không tồn tại. |
| 400 | `Author is not active` | User tồn tại nhưng status khác `ACTIVE`. |
| 503 | `User Service returned server error` | User Service trả 5xx. |
| 503 | `Failed to call User Service` | Lỗi kết nối/HTTP client khi gọi User Service. |
| 500 | `Internal server error` | Exception không được phân loại. |

Ví dụ error body:

```json
{
  "success": false,
  "message": "Author is not active",
  "data": null
}
```

HTTP response chỉ xác nhận post đã được kiểm tra và logged batch Cassandra đã hoàn tất; không đảm bảo search index/notification đã sẵn sàng hoặc Kafka đã acknowledge message.

### Lấy danh sách post

```http
GET /api/feed/posts
```

Ví dụ:

```powershell
Invoke-RestMethod "http://localhost:8082/api/feed/posts"
```

Response `200 OK`:

```json
{
  "success": true,
  "message": "Posts found",
  "data": [
    {
      "postId": "5b990c7c-72b1-4af3-9f50-66c56d9ee94d",
      "authorId": "u001",
      "content": "Spring Boot and Kafka",
      "createdAt": "2026-07-14T03:15:30.123"
    }
  ]
}
```

Danh sách được sắp xếp theo `createdAt` giảm dần. API chưa có pagination; khi không có post, `data` là `[]`.

### Lấy một post

```http
GET /api/feed/posts/{postId}
```

Ví dụ:

```powershell
Invoke-RestMethod "http://localhost:8082/api/feed/posts/5b990c7c-72b1-4af3-9f50-66c56d9ee94d"
```

Response `200 OK` có `data` là một `PostResponse` giống schema phía trên.

Nếu không tìm thấy, code hiện trả `400 Bad Request`:

```json
{
  "success": false,
  "message": "Post not found",
  "data": null
}
```

Đây là hành vi hiện tại; API production thường nên dùng `404 Not Found`.

## 4. Search Service

### Tìm post theo nội dung

```http
GET /api/search/posts?keyword={keyword}
```

Ví dụ:

```powershell
Invoke-RestMethod "http://localhost:8083/api/search/posts?keyword=kafka"
```

Response `200 OK`:

```json
{
  "success": true,
  "message": "Search result",
  "data": [
    {
      "postId": "5b990c7c-72b1-4af3-9f50-66c56d9ee94d",
      "authorId": "u001",
      "content": "Spring Boot and Kafka"
    }
  ]
}
```

Đặc tính tìm kiếm hiện tại:

- Không phân biệt hoa/thường.
- Match theo substring trên toàn bộ `content`.
- Không tìm theo author/post ID.
- Không bảo đảm thứ tự kết quả do dữ liệu đến từ `ConcurrentHashMap`.
- Không có pagination hoặc scoring.
- Keyword rỗng match mọi post; thiếu query parameter làm Spring trả HTTP 400 theo error format mặc định của framework.

Search index được cập nhật bất đồng bộ. Post vừa tạo có thể chưa xuất hiện ngay; index cũng mất hoàn toàn khi restart `search-service` và không tự rebuild từ Feed Service.

## 5. Notification Service

### Lấy notification theo user

```http
GET /api/notifications/users/{userId}
```

Ví dụ:

```powershell
Invoke-RestMethod "http://localhost:8084/api/notifications/users/u001"
```

Response `200 OK`:

```json
{
  "success": true,
  "message": "Notifications found",
  "data": [
    {
      "notificationId": "253ca64a-4d4f-46d2-baf7-4bf3da81fc0a",
      "userId": "u001",
      "title": "Post created",
      "message": "Your post 5b990c7c-72b1-4af3-9f50-66c56d9ee94d was created successfully",
      "createdAt": "2026-07-14T10:15:31.456789"
    }
  ]
}
```

Endpoint luôn trả 200; user không có notification nhận `data: []`. Thứ tự notification không được bảo đảm và chưa có pagination/read status.

## 6. cURL tương đương

Nếu không dùng PowerShell:

```bash
curl -X POST http://localhost:8082/api/feed/posts \
  -H 'Content-Type: application/json' \
  -d '{"authorId":"u001","content":"Spring Boot and Kafka"}'

curl 'http://localhost:8082/api/feed/posts'
curl 'http://localhost:8083/api/search/posts?keyword=kafka'
curl 'http://localhost:8084/api/notifications/users/u001'
```
