# Redis Cache Documentation Design

## Mục tiêu

Tạo một tài liệu tiếng Việt, có thể thực hiện theo từng bước, mô tả Redis và
cache hai tầng của Feed Service. Người đọc có thể cấu hình, quan sát và xác
minh cache mà không phải suy đoán từ source code.

## Phạm vi

- Tạo `docs/REDIS_CACHE.md`.
- Liên kết tài liệu mới từ `docs/README.md` và `README.md`.
- Không thay đổi hành vi runtime, cấu hình mặc định, API hoặc source Java.

## Nội dung của REDIS_CACHE.md

1. Mục đích và phạm vi: cache là private/disposable của Feed Service; Cassandra
   vẫn là nguồn dữ liệu cho post/feed.
2. Kiến trúc và luồng đọc/ghi: Caffeine L1, Redis L2, Cassandra, revision của
   feed và Redis Pub/Sub invalidation.
3. Key, TTL và giới hạn: key post/page/revision, TTL mặc định, jitter, giới hạn
   page size và nguyên tắc không dùng Redis `KEYS`/`SCAN` để xóa theo prefix.
4. Cấu hình: các biến Redis và `app.cache` được source hỗ trợ, gồm cách tắt
   cache bằng `CACHE_ENABLED=false`.
5. Vận hành local: khởi động Compose, xác minh health/log/Redis CLI và an toàn
   khi xem key.
6. Kiểm thử: unit test, integration test Testcontainers, và checklist kiểm tra
   thủ công qua API Gateway cùng Redis CLI/Actuator metrics.
7. Troubleshooting: Redis unavailable, cache miss kỳ vọng, invalidation và
   cảnh báo fail-open.

## Liên kết

- `docs/README.md` thêm Redis Cache vào mục phát triển source, vận hành local
  và bảng bản đồ tài liệu.
- `README.md` thêm liên kết ngắn trong phần Documentation.

## Tiêu chí chấp nhận

- Mọi giá trị cấu hình, luồng và tên metric nêu trong tài liệu đều có trong
  source hiện tại.
- Các lệnh không yêu cầu công cụ ngoài Docker Compose, curl, redis-cli và Maven
  hoặc Maven wrapper khi repository có sẵn chúng.
- Tài liệu phân biệt rõ kiểm tra cache với kiểm tra tính đúng đắn của Cassandra.
