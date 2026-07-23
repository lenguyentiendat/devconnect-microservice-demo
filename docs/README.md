# Mục lục tài liệu DevConnect

Trang này là điểm bắt đầu cho toàn bộ tài liệu của project. Chọn lộ trình phù hợp với nhu cầu:

## Chạy thử nhanh

1. Đọc [README project](../README.md) để hiểu mục tiêu và kiến trúc tổng quan.
2. Đọc [Docker và Ubuntu/WSL](DOCKER.md) để chạy toàn bộ 9 service bằng một lệnh.
3. Dùng [REST API reference](API.md) để kiểm tra luồng end-to-end.
4. Mở [Swagger/OpenAPI guide](OPENAPI.md) để truy cập Swagger UI tổng hợp cho toàn bộ service.

## Phát triển source

1. [Kiến trúc hệ thống](ARCHITECTURE.md): boundary của service, luồng sync/async, consistency và scaling.
2. [Phát triển và kiểm thử](DEVELOPMENT.md): Maven workflow, chạy trên host, biến môi trường và test strategy.
3. [PostgreSQL và Cassandra](DATABASE.md): schema, migration, query model và kiểm tra dữ liệu.
4. [Kafka event reference](EVENTS.md): topic, payload, consumer group, delivery và compatibility.
5. [Async Java](../ASYNC-JAVA.md): parallel validation bằng `CompletableFuture`, executor, OpenFeign blocking I/O và Kafka async.
6. [Redis và Cache](REDIS_CACHE.md): cache hai tầng của Feed Service, cấu hình, vận hành và kiểm thử.

## Vận hành local

- [Docker và Ubuntu/WSL](DOCKER.md): build image, healthcheck, log, restart, cập nhật source và troubleshooting.
- [PostgreSQL và Cassandra](DATABASE.md): volume, truy vấn trực tiếp và reset dữ liệu.
- [Redis và Cache](REDIS_CACHE.md): Redis local, cache keys, TTL, metrics và fallback.
- [Phát triển và kiểm thử](DEVELOPMENT.md#8-troubleshooting): lỗi ứng dụng thường gặp.

## Bản đồ tài liệu

| Tài liệu | Nội dung chính | Đối tượng |
|---|---|---|
| [README](../README.md) | Tổng quan và quick start | Tất cả |
| [ARCHITECTURE](ARCHITECTURE.md) | Thiết kế service và luồng dữ liệu | Developer/architect |
| [API](API.md) | REST contract, status code và ví dụ | Client/backend developer |
| [OPENAPI](OPENAPI.md) | Swagger UI tổng hợp và OpenAPI JSON cho 4 service | Client/backend developer |
| [EVENTS](EVENTS.md) | Kafka contract và delivery semantics | Backend/platform developer |
| [REDIS_CACHE](REDIS_CACHE.md) | Cache Caffeine/Redis, invalidation và xác minh | Backend/platform developer |
| [DATABASE](DATABASE.md) | PostgreSQL/Flyway và Cassandra model | Backend/DBA |
| [DOCKER](DOCKER.md) | Full-stack Compose trên Ubuntu/WSL | Developer/operator |
| [DEVELOPMENT](DEVELOPMENT.md) | Maven, test, cấu hình và debug | Developer |
| [ASYNC-JAVA](../ASYNC-JAVA.md) | Parallelism trong Feed Service và async qua Kafka | Java developer |

## Thông tin hệ thống cố định

- 4 Spring Boot application: User, Feed, Search, Notification.
- 5 infrastructure/utility service: PostgreSQL, Cassandra, Cassandra Init, Kafka, Kafka UI.
- Tổng số Compose service: 9.
- PostgreSQL sở hữu user data; Cassandra sở hữu post data.
- Search index và notification vẫn lưu trong memory; process mới rebuild từ event Kafka còn retention.
- Kafka topic nghiệp vụ: `post-events`.

Khi source, API, event, schema hoặc Compose thay đổi, cập nhật tài liệu tương ứng trong cùng pull request/commit.
