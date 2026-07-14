# User Service host port 3000

## Mục tiêu

Tránh xung đột với process đang chiếm cổng `8081` trên Ubuntu, đồng thời không thay đổi giao tiếp nội bộ giữa các container.

## Thiết kế đã chọn

- Docker Compose publish User Service bằng mapping `3000:8081`.
- User Service tiếp tục lắng nghe cổng `8081` bên trong container.
- Healthcheck tiếp tục kiểm tra `127.0.0.1:8081` trong container.
- Feed Service tiếp tục gọi `http://user-service:8081` qua Compose network.
- Client trên host sử dụng `http://localhost:3000`.
- Khi chạy User Service trực tiếp trên host, cổng mặc định vẫn là `8081`; có thể đặt `USER_SERVICE_PORT=3000` nếu muốn tránh xung đột trong workflow đó.

## Phạm vi thay đổi

- Đổi host port mapping của `user-service` trong `docker-compose.yml`.
- Cập nhật toàn bộ tài liệu và lệnh kiểm thử dùng URL User Service từ host.
- Giữ nguyên Dockerfile, application configuration và internal service URL.

## Xác minh

- Kiểm tra cấu hình Compose render mapping host `3000` tới container `8081`.
- Kiểm tra tài liệu Compose/API hướng dẫn client host gọi `localhost:3000`; `localhost:8081` chỉ còn trong hướng dẫn chạy application trực tiếp trên host với cấu hình mặc định.
- Kiểm tra các tham chiếu nội bộ `user-service:8081` và healthcheck `8081` vẫn còn nguyên.
- Chạy toàn bộ Maven test để phát hiện regression.
