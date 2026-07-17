# Swagger / OpenAPI

Project cung cấp **một Swagger UI duy nhất** để demo toàn bộ API trên cùng một trang. Container `swagger-spec` lấy OpenAPI JSON live từ bốn service và gộp thành một document; mỗi service vẫn expose JSON contract nội bộ tại `/v3/api-docs`.

## Swagger UI duy nhất

Sau khi khởi động Compose và các service healthy, mở:

**http://localhost:8090/**

Toàn bộ tag/endpoint của User, Feed, Search và Notification hiển thị trong cùng một danh sách; không còn dropdown nhiều definition.

Các OpenAPI JSON nguồn:

| Service | OpenAPI JSON |
|---|---|
| User | http://localhost:3000/v3/api-docs |
| Feed | http://localhost:8082/v3/api-docs |
| Search | http://localhost:8083/v3/api-docs |
| Notification | http://localhost:8084/v3/api-docs |

## API được mô tả

- User: tạo/cập nhật user và tra cứu status nội bộ.
- Feed: tạo, liệt kê và đọc post.
- Search: tìm post theo keyword.
- Notification: liệt kê notification theo user.

Endpoint internal của User Service vẫn được đưa vào spec để kiểm tra service-to-service contract. Schema status nội bộ không chứa email.

## Chạy local

```powershell
docker compose down --remove-orphans
docker compose up -d --build
docker compose ps -a
```

Trong WSL, chạy từ đúng repository mới:

```bash
cd /mnt/d/devconnect-microservice-demo
# hoặc: cd ~/devconnect-microservice-demo
docker compose config --services
```

Danh sách phải có `notification-service` và `swagger-ui`. Nếu dùng bản copy trong home, đồng bộ nội dung (không copy cả thư mục lồng nhau):

```bash
cp -r /mnt/d/devconnect-microservice-demo/. ~/devconnect-microservice-demo/
```

Compose dùng network external tên `devconnect-network`. Tạo một lần nếu chưa tồn tại:

```bash
docker network inspect devconnect-network >/dev/null 2>&1 || \
  docker network create devconnect-network
```

Hoặc kiểm tra source/build trước:

```powershell
mvn clean verify
```

Swagger UI tổng hợp được khai báo trong `docker-compose.yml` bằng image `swaggerapi/swagger-ui`, map host port `8090` và load file `/out/openapi.json` do `swagger-spec` tạo. Docker image này phục vụ UI tại `/` (không phải `/swagger-ui.html`). CORS chỉ mở cho origin `http://localhost:8090` trên các endpoint `/v3/api-docs/**`.

Khi API contract thay đổi, chạy lại generator:

```bash
docker compose up --force-recreate swagger-spec
docker compose restart swagger-ui
```

Nếu UI không mở, kiểm tra:

```powershell
docker compose ps -a
docker compose logs --tail=200 swagger-ui
docker compose logs --tail=200 user-service feed-service
```

Khi build image sau khi cập nhật source/Dockerfile, dùng `docker compose build --no-cache`.

## Ghi chú

- OpenAPI JSON được tạo live bởi springdoc-openapi 3.0.3.
- Swagger UI tổng hợp là entry point duy nhất dành cho presentation; route/payload/status code/error envelope của business API không thay đổi.
- Email xuất hiện trong public User profile request/response, không xuất hiện trong internal status response.
