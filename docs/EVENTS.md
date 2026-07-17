# Kafka event reference

> Điều hướng: [Mục lục](README.md) · [Kiến trúc](ARCHITECTURE.md) · [API](API.md) · [Docker](DOCKER.md)

## 1. Topic

| Thuộc tính | Giá trị hiện tại |
|---|---|
| Topic | `post-events` |
| Producer | `feed-service` |
| Message key | `postId` |
| Value format | JSON |
| Consumer group 1 | `search-service-group` |
| Consumer group 2 | `notification-service-group` |
| Topic creation local | Kafka auto-create được bật trong Docker Compose |

Tên topic được đọc từ `app.kafka.topics.post-events`. Hiện giá trị này được viết trực tiếp trong YAML và chưa có environment-variable placeholder.

## 2. `POST_CREATED`

Event được publish sau khi post đã được lưu vào hai Cassandra read model của `feed-service`.

Schema logic:

| Field | Kiểu | Ý nghĩa |
|---|---|---|
| `eventId` | string (UUID) | ID duy nhất cho một lần phát event; notification dùng để deduplicate. |
| `eventType` | string | Hiện chỉ xử lý giá trị `POST_CREATED`. |
| `postId` | string (UUID) | ID của post, đồng thời là Kafka message key. |
| `authorId` | string | User tạo post. |
| `content` | string | Nội dung dùng để xây search index. |
| `occurredAt` | string (ISO local datetime) | Thời điểm tạo event, không kèm timezone/offset. |

Ví dụ payload:

```json
{
  "eventId": "f4233e89-71f0-46ed-846f-0f4261d0eb86",
  "eventType": "POST_CREATED",
  "postId": "5b990c7c-72b1-4af3-9f50-66c56d9ee94d",
  "authorId": "u001",
  "content": "Spring Boot and Kafka",
  "occurredAt": "2026-07-14T03:15:30.123"
}
```

## 3. Serialization/deserialization

Producer dùng:

```text
key-serializer   = StringSerializer
value-serializer = JsonSerializer
```

Mỗi consumer dùng `JsonDeserializer`, không sử dụng type header của producer và ép payload về class `PostCreatedEvent` thuộc package của chính service:

```text
spring.json.use.type.headers = false
spring.json.value.default.type = <consumer package>.PostCreatedEvent
```

Cách này tránh việc consumer phải có đúng Java package của producer, nhưng schema giữa ba record vẫn phải tương thích về field name/type.

## 4. Delivery và idempotency

Producer bật:

- `acks=all`
- `retries=3`
- `enable.idempotence=true`

Các thuộc tính này giúp giảm duplicate do retry ở producer và yêu cầu broker acknowledge chặt hơn. Tuy nhiên application không chờ future hoàn tất trước khi trả API response; callback chỉ ghi log thành công/thất bại.

Consumer behavior:

| Consumer | Duplicate event | Event type khác |
|---|---|---|
| Search | Upsert theo `postId`, kết quả cuối không nhân bản record. | Bỏ qua. |
| Notification | Bỏ qua nếu `eventId` đã thấy trong process hiện tại. | Bỏ qua. |

Search và Notification implement partition-assignment callback để seek các partition được gán về đầu đúng một lần trong mỗi process. Vì vậy committed offset không ngăn việc rebuild read model sau restart. Search upsert theo `postId`; Notification dựng lại `processedEventIds` theo `eventId`. Các rebalance tiếp theo trong cùng process không replay lại. Cơ chế này chỉ phục hồi event còn trong Kafka retention và chỉ phù hợp mô hình một instance cho mỗi consumer service.

## 5. Compatibility rule khuyến nghị

Hiện event chưa có `schemaVersion`. Khi mở rộng demo:

- Chỉ thêm field optional/default để giữ backward compatibility.
- Không đổi nghĩa hoặc kiểu của field đang tồn tại.
- Thêm `schemaVersion` trước khi có nhiều event version.
- Có contract test giữa producer và mỗi consumer.
- Với thay đổi breaking, tạo event/topic version mới hoặc dùng schema registry có compatibility policy.
- Chuyển timestamp sang `Instant`/UTC offset để tránh diễn giải khác timezone.

## 6. Quan sát bằng Kafka UI

Sau khi chạy `docker compose up -d --build`, mở <http://localhost:8085>:

1. Chọn cluster `devconnect-local`.
2. Mở topic `post-events`.
3. Xem Messages để kiểm tra key/payload/partition/offset.
4. Xem Consumers để kiểm tra hai consumer group và lag.

Kafka UI chỉ là công cụ local trong compose, không có authentication và không nên expose công khai.

## 7. Kiểm tra bằng Kafka CLI

Liệt kê topic:

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list
```

Mô tả topic:

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic post-events
```

Kiểm tra consumer group và lag:

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --group search-service-group

docker compose exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --group notification-service-group
```

Trong Kafka container, `localhost:9092` là host listener của chính broker. Application container sử dụng listener nội bộ `kafka:29092`.
