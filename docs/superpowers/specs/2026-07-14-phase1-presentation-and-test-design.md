# Phase 1 presentation và test guide design

## Mục tiêu

Chuẩn bị bộ tài liệu giúp trình bày toàn bộ DevConnect Phase 1 trong 10–15 phút cho developer, đồng thời cung cấp runbook test đầy đủ để demo và xác minh hệ thống.

## Cách kể chuyện

Presentation dùng cấu trúc story-first, đi theo use case trung tâm “tạo post”:

1. Tác giả phải tồn tại và có trạng thái `ACTIVE`.
2. Feed Service xác minh tác giả qua HTTP đồng bộ.
3. Post được lưu vào hai Cassandra read model.
4. Feed Service publish `POST_CREATED` lên Kafka.
5. Search và Notification xử lý event bất đồng bộ.
6. Client quan sát kết quả theo eventual consistency.

Cách này ưu tiên một luồng xuyên suốt thay vì giới thiệu từng component rời rạc.

## Deliverable

### `docs/PHASE1-PRESENTATION.md`

Tài liệu dùng trực tiếp khi thuyết trình, gồm:

- Timeline 10–15 phút.
- Mục tiêu và phạm vi Phase 1.
- Sơ đồ system context của 9 Compose service.
- Sequence diagram cho create-post happy path và error branch.
- Sơ đồ data ownership và Kafka consumer flow.
- Lời thoại gợi ý theo từng phần.
- Kịch bản live demo ngắn.
- Kết quả Phase 1, giới hạn và hướng Phase 2.

### `docs/PHASE1-TEST-GUIDE.md`

Runbook test copy/paste trên Ubuntu, gồm:

- Xác nhận đúng source và đủ 9 Compose service.
- Startup, trạng thái container và healthcheck.
- PostgreSQL schema, Flyway và seed user.
- User API: active, inactive và not-found.
- Feed validation và create-post happy path.
- Đọc post từ API và hai Cassandra table.
- Kafka topic, event, consumer group và lag.
- Search case-insensitive/substring.
- Notification theo user.
- Persistence và giới hạn read model in-memory sau restart.
- Maven automated test.
- Checklist pass/fail và troubleshooting nhanh.

### `docs/README.md`

Thêm liên kết tới hai tài liệu mới trong mục lục chung.

## Sơ đồ

Tất cả sơ đồ dùng Mermaid để render được trên GitHub/VS Code:

1. `flowchart`: client, 4 application, PostgreSQL, Cassandra, Kafka và Kafka UI.
2. `sequenceDiagram`: request từ client qua Feed/User/database/Kafka/consumer.
3. `flowchart`: trình tự test từ infrastructure tới business outcome.

Sơ đồ phải phân biệt rõ:

- Đồng bộ: Feed → User → PostgreSQL.
- Persistent write: Feed → Cassandra.
- Bất đồng bộ: Feed → Kafka → Search/Notification.
- Host port User Service là `3000`; container/internal port là `8081`.

## Timeline presentation

| Thời lượng | Nội dung |
|---:|---|
| 1 phút | Bài toán và mục tiêu Phase 1. |
| 2 phút | Kiến trúc tổng thể. |
| 3 phút | Main flow tạo post. |
| 2 phút | Data ownership và consistency. |
| 3–4 phút | Live demo/test. |
| 1–2 phút | Kết quả, giới hạn và Phase 2. |

## Nguyên tắc nội dung

- Nội dung đủ kỹ thuật cho developer nhưng không sa vào chi tiết class-by-class.
- Mỗi phần presentation có “ý cần nói”, “điểm cần nhấn mạnh” và thời lượng.
- Lệnh test dùng Bash/Ubuntu và phù hợp Docker Compose hiện tại.
- Các bước bất đồng bộ dùng polling có giới hạn, không phụ thuộc một `sleep` cố định.
- Phân biệt hành vi hiện tại với đề xuất production.
- Không mô tả Search/Notification là persistent; cả hai vẫn dùng in-memory read model.

## Tiêu chí hoàn thành

- Người trình bày có thể đi hết nội dung trong 10–15 phút.
- Ba sơ đồ thể hiện đúng boundary, port, data ownership và luồng sync/async.
- Test guide bao phủ infrastructure, API, database, event và consumer outcome.
- Mọi link Markdown nội bộ hợp lệ và code fence được đóng đầy đủ.
- Các endpoint, port, table, topic và consumer group khớp source hiện tại.

