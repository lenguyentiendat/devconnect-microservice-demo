# Feed OpenFeign Documentation Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Đồng bộ tài liệu hiện hành với contract create-post và giao tiếp OpenFeign đã triển khai giữa Feed Service và User Service.

**Architecture:** Chỉ cập nhật tài liệu dành cho người dùng/developer; các spec và plan lịch sử được giữ nguyên. Tài liệu phải mô tả controller đồng bộ trả `ResponseEntity<PostResponse>`/HTTP 201, hai validation chạy song song trong `FeedService`, và `UserServiceClient` + `UserServiceAdapter` làm internal HTTP boundary.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Cloud 2025.1.2, Spring Cloud OpenFeign, `CompletableFuture`, Cassandra, Kafka.

## Global Constraints

- Giữ nguyên các thay đổi tài liệu Kafka replay đang có trong worktree.
- Không mô tả `UserServiceAdapter` như API Gateway.
- Không thay đổi source code, API contract hoặc runtime configuration.
- Không sửa các tài liệu lịch sử trong `docs/superpowers/specs/` và `docs/superpowers/plans/`, ngoài plan này.

---

### Task 1: Đồng bộ tổng quan và kiến trúc

**Files:**
- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/README.md`

- [x] Mô tả OpenFeign, adapter boundary, HTTP 201 và parallel validation bằng `thenCombine()`.
- [x] Xóa mô tả `AsyncPostService`, async servlet controller và `RestClient` khỏi tài liệu hiện hành.
- [x] Phân biệt internal Feign client với API Gateway ở edge.

### Task 2: Đồng bộ API và hướng dẫn vận hành

**Files:**
- Modify: `docs/API.md`
- Modify: `docs/DEVELOPMENT.md`
- Modify: `docs/DOCKER.md`
- Modify: `docs/DATABASE.md`

- [x] Cập nhật response create-post thành raw `PostResponse` với HTTP 201.
- [x] Cập nhật content rules, Feign error mapping, URL và timeout 5 giây.
- [x] Cập nhật danh sách test theo các class hiện tại.

### Task 3: Viết lại hướng dẫn async

**Files:**
- Modify: `ASYNC-JAVA.md`

- [x] Giải thích parallelism nội bộ và synchronous HTTP contract hiện tại.
- [x] Nêu rõ `join()` vẫn block request thread và lợi ích chỉ là giảm latency của hai task độc lập.
- [x] Giữ riêng phần Kafka asynchronous/eventual consistency.

### Task 4: Kiểm chứng

- [x] Quét tài liệu hiện hành để không còn `RestClient`, `AsyncPostService` hoặc controller trả `CompletableFuture`.
- [x] Kiểm tra Markdown diff, whitespace và các giá trị URL/timeout/status code.
- [x] Chạy `mvn clean test` toàn reactor để xác nhận contract và tổng 50 test đang hoạt động.
