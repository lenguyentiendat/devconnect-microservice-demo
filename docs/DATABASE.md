# PostgreSQL và Cassandra

> Điều hướng: [Mục lục](README.md) · [Kiến trúc](ARCHITECTURE.md) · [Docker](DOCKER.md) · [Development](DEVELOPMENT.md)

DevConnect dùng polyglot persistence: mỗi loại dữ liệu được lưu trong database phù hợp với query và consistency requirement của service sở hữu nó.

## 1. Quyền sở hữu dữ liệu

| Dữ liệu | Owner | Storage | Trạng thái persistence |
|---|---|---|---|
| User/status | User Service | PostgreSQL | Persistent qua named volume. |
| Post/feed | Feed Service | Cassandra | Persistent qua named volume. |
| Search index | Search Service | `ConcurrentHashMap` | Map mất khi restart; rebuild từ event Kafka còn retention. |
| Notification/dedup | Notification Service | `ConcurrentHashMap` | Map mất khi restart; rebuild từ event Kafka còn retention. |

Service không truy cập trực tiếp database của service khác. Feed Service hỏi User Service qua OpenFeign HTTP client (`GET /internal/users/{userId}/status`) thay vì query PostgreSQL.

## 2. PostgreSQL của User Service

### Kết nối local

| Thuộc tính | Giá trị Compose |
|---|---|
| Host từ máy local | `localhost:5432` |
| Host trong Compose network | `postgres:5432` |
| Database | `devconnect_users` |
| Username | `devconnect` |
| Password | `devconnect` |
| Volume | `postgres-data` |

Application đọc cấu hình từ `POSTGRES_URL`, `POSTGRES_USERNAME`, `POSTGRES_PASSWORD`.

### Schema do Hibernate quản lý

User Service dùng `spring.jpa.hibernate.ddl-auto=update`. Khi kết nối tới một
database PostgreSQL rỗng, Hibernate tạo và đồng bộ bảng `users` từ
`UserEntity`:

| Cột | Ràng buộc từ mapping |
|---|---|
| `user_id` | Primary key, tối đa 64 ký tự |
| `status` | Bắt buộc, tối đa 32 ký tự |
| `email` | Nullable, tối đa 254 ký tự, unique |

Email được chuẩn hóa thành chữ thường trước khi lưu, vì vậy unique constraint
trên `email` cũng chặn email trùng khác hoa/thường. Service vẫn kiểm tra trước
khi ghi để trả HTTP 409 rõ ràng.

Không có user demo được tự động seed. Tạo user bằng API trước khi dùng Feed
Service:

```bash
curl -X POST http://localhost:8081/api/users \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u001","status":"ACTIVE","email":"u001@example.com"}'
```

`ddl-auto=update` phù hợp cho môi trường local/demo và thay đổi schema có tính
bổ sung. Với thay đổi phá vỡ hoặc dữ liệu production, cần một quy trình quản
trị database riêng; Hibernate không thay thế kế hoạch backup/migration vận
hành.

### Kiểm tra dữ liệu

```bash
docker compose exec postgres \
  psql -U devconnect -d devconnect_users \
  -c "SELECT user_id, status, email FROM users ORDER BY user_id;"
```

## 3. Cassandra của Feed Service

### Kết nối local

| Thuộc tính | Giá trị Compose |
|---|---|
| Contact point từ máy local | `localhost:9042` |
| Contact point trong Compose | `cassandra:9042` |
| Local datacenter | `datacenter1` |
| Keyspace | `devconnect_feed` |
| Volume | `cassandra-data` |

Application đọc `CASSANDRA_CONTACT_POINTS`, `CASSANDRA_LOCAL_DATACENTER`, `CASSANDRA_KEYSPACE`, `CASSANDRA_REQUEST_TIMEOUT`.

### Keyspace

One-shot service `cassandra-init` chạy:

```cql
CREATE KEYSPACE IF NOT EXISTS devconnect_feed
WITH replication = {
    'class': 'NetworkTopologyStrategy',
    'datacenter1': 1
};
```

Replication factor 1 chỉ phù hợp single-node local environment.

### Table theo query

Feed Service cấu hình `schema-action=create-if-not-exists`, nên Spring Data Cassandra tạo table từ entity mapping.

Query 1 — danh sách feed mới nhất trước:

```cql
CREATE TABLE posts_by_feed (
    feed_id text,
    created_at timestamp,
    post_id uuid,
    author_id text,
    content text,
    PRIMARY KEY ((feed_id), created_at, post_id)
) WITH CLUSTERING ORDER BY (created_at DESC, post_id ASC);
```

Query:

```cql
SELECT * FROM posts_by_feed WHERE feed_id = 'global';
```

Query 2 — lookup trực tiếp theo post ID:

```cql
CREATE TABLE posts_by_id (
    post_id uuid PRIMARY KEY,
    author_id text,
    content text,
    created_at timestamp
);
```

Cassandra không join hai table. Hai table chứa dữ liệu post lặp lại có chủ đích để phục vụ hai query khác nhau mà không dùng `ALLOW FILTERING`.

### Write path

Một create-post tạo:

1. Một row trong `posts_by_feed`.
2. Một row trong `posts_by_id`.
3. Cả hai được đưa vào một logged Cassandra batch.

Logged batch bảo đảm atomicity cho hai row của một post. Đây là multi-partition batch nhỏ; không mở rộng cách này thành bulk batch vì coordinator cost tăng nhanh.

Sau khi batch thành công, Feed Service bắt đầu publish Kafka event. Cassandra write và Kafka publish không cùng transaction; Kafka lỗi có thể để lại post không có search/notification.

### Timestamp

- Feed Service tạo thời gian theo UTC.
- Thời gian được truncate tới millisecond để khớp Cassandra `timestamp`.
- API vẫn trả `LocalDateTime` không có offset; client nên hiểu Feed timestamp là UTC.

### Kiểm tra dữ liệu

Liệt kê table:

```bash
docker compose exec cassandra \
  cqlsh -e "DESCRIBE KEYSPACE devconnect_feed"
```

Đọc feed:

```bash
docker compose exec cassandra \
  cqlsh -e "SELECT post_id, author_id, content, created_at FROM devconnect_feed.posts_by_feed WHERE feed_id = 'global';"
```

Đọc theo ID:

```bash
POST_ID="5b990c7c-72b1-4af3-9f50-66c56d9ee94d"
docker compose exec cassandra \
  cqlsh -e "SELECT * FROM devconnect_feed.posts_by_id WHERE post_id = ${POST_ID};"
```

## 4. Persistence lifecycle

`docker compose down` giữ named volume. `docker compose down -v` xóa cả `postgres-data` và `cassandra-data`.

Sau reset:

1. PostgreSQL tạo database rỗng; User Service dùng Hibernate để tạo schema. Tạo user qua `POST /api/users` khi cần dữ liệu demo.
2. Cassandra Init tạo keyspace.
3. Feed Service tạo table khi khởi động.

Search/Notification không dùng volume. Mỗi process mới seek Kafka về đầu đúng một lần và dựng lại map từ các event còn retention; dữ liệu vẫn không phải persistent storage của service.

## 5. Giới hạn mô hình hiện tại

- `posts_by_feed` dùng partition `global`, sẽ thành hot và unbounded partition khi dữ liệu lớn.
- Chưa có pagination; endpoint danh sách đọc toàn bộ partition.
- Chưa có backup/restore automation.
- Cassandra schema auto-create phù hợp demo; production nên quản lý CQL migration có version.
- Credential PostgreSQL hard-code trong Compose, không dùng secret manager.
- Không có TLS/authentication cho Cassandra local.
- Không có transaction/outbox giữa Cassandra và Kafka.

Hướng production cho feed là bucket partition theo tenant/user/time window, thêm pagination state, schema migration rõ ràng và outbox/CDC hoặc một thiết kế lưu post/outbox có atomic boundary.
