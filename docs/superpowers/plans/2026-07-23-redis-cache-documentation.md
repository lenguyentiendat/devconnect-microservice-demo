# Redis Cache Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cung cấp tài liệu tiếng Việt có thể thực thi để hiểu, vận hành và xác minh Redis/Caffeine cache của Feed Service.

**Architecture:** Một tài liệu chuyên biệt tại `docs/REDIS_CACHE.md` là nguồn thông tin về cache. Hai mục lục hiện có chỉ liên kết tới tài liệu này, không lặp lại chi tiết kỹ thuật.

**Tech Stack:** Spring Boot, Caffeine, Redis 7, Redis Pub/Sub, Micrometer Actuator, Docker Compose, Testcontainers.

## Global Constraints

- Không thay đổi source Java, hành vi runtime, API hoặc cấu hình mặc định.
- Chỉ mô tả property, key, TTL và metric có trong source hiện tại.
- Không hướng dẫn dùng Redis `KEYS` hoặc `SCAN` để xóa cache theo prefix.
- Các lệnh kiểm tra local dùng Docker Compose, `curl` và `redis-cli` có thể chạy từ container Redis.

---

### Task 1: Viết tài liệu vận hành Redis Cache

**Files:**

- Create: `docs/REDIS_CACHE.md`
- Reference: `feed-service/src/main/java/com/devconnect/feed/config/CacheConfiguration.java`
- Reference: `feed-service/src/main/java/com/devconnect/feed/cache/TwoLevelCacheService.java`
- Reference: `feed-service/src/main/java/com/devconnect/feed/cache/CacheKeyFactory.java`
- Reference: `feed-service/src/main/java/com/devconnect/feed/service/FeedService.java`
- Reference: `feed-service/src/main/resources/application.yaml`
- Reference: `docker-compose.yml`

**Interfaces:**

- Consumes: `app.cache.*`, `REDIS_*`, endpoint `/api/feed/posts/{postId}`, Redis service `redis`.
- Produces: Hướng dẫn độc lập để cấu hình, kiểm tra key/TTL, metric và fallback.

- [x] **Step 1: Viết phần kiến trúc và hành vi cache**

Mô tả Caffeine L1 theo process, Redis L2 dùng chung, Cassandra là nguồn dữ liệu; luồng đọc L1 → L2 → Cassandra và luồng ghi post → revision → Pub/Sub → cache post.

- [x] **Step 2: Viết phần key, TTL và cấu hình**

Liệt kê dạng key `devconnect:{environment}:feed:v1:post:{postId}`, revision global và key page có revision/cursor hash; ghi TTL mặc định post L1/L2 `45s`/`5m`, page `10s`/`60s`, jitter `0..10%`, `l1-maximum-size=10000` và `CACHE_ENABLED=false`.

- [x] **Step 3: Viết checklist kiểm tra thủ công**

Bao gồm `docker compose up -d --build`, `docker compose exec redis redis-cli ping`, gọi post hai lần qua Gateway, xem key bằng `redis-cli --scan --pattern 'devconnect:local:feed:v1:*'`, `TTL`, truy vấn actuator metric `feed.cache.redis.errors`, và kiểm tra fallback bằng cách dừng Redis rồi gọi API đọc.

- [x] **Step 4: Viết phần kiểm thử tự động và troubleshooting**

Nêu `RedisCacheIntegrationTests` (Testcontainers/Docker) và các unit test cache; giải thích điều kiện Docker/JDK/Maven. Hướng dẫn xử lý Redis down, key không xuất hiện, cache tắt, cache page đổi revision và lỗi invalidation.

- [x] **Step 5: Kiểm tra tài liệu**

Run: `git diff --check -- docs/REDIS_CACHE.md && rg -n 'KEYS|SCAN' docs/REDIS_CACHE.md`

Expected: kiểm tra whitespace không báo lỗi; các từ `KEYS`/`SCAN` chỉ xuất hiện trong câu cấm sử dụng chúng để invalidation.

### Task 2: Liên kết tài liệu từ mục lục

**Files:**

- Modify: `docs/README.md`
- Modify: `README.md`

**Interfaces:**

- Consumes: `docs/REDIS_CACHE.md` từ Task 1.
- Produces: Liên kết dễ tìm từ cả mục lục tài liệu lẫn README repository.

- [x] **Step 1: Thêm Redis Cache vào docs index**

Thêm một mục dưới “Phát triển source”, một mục dưới “Vận hành local”, và một hàng trong bảng “Bản đồ tài liệu”, tất cả liên kết tương đối tới `REDIS_CACHE.md`.

- [x] **Step 2: Thêm liên kết vào README repository**

Thêm bullet `[Redis và Cache](docs/REDIS_CACHE.md)` trong phần Documentation, không lặp lại nội dung trong tài liệu mới.

- [x] **Step 3: Kiểm tra liên kết và định dạng**

Run: `rg -n '\\]\((\\.\\./)?REDIS_CACHE\\.md\\)' README.md docs/README.md && git diff --check`

Expected: một liên kết từ `README.md`, ba liên kết từ `docs/README.md`, không có lỗi whitespace.

- [x] **Step 4: Commit**

```bash
git add README.md docs/README.md docs/REDIS_CACHE.md docs/superpowers/plans/2026-07-23-redis-cache-documentation.md
git commit -m "docs: add Redis cache guide"
```
