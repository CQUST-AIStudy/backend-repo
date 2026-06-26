# 后端 Docker 部署指南

## 部署形态

当前仓库只打包并运行 Spring Boot 后端容器，不在本仓库内启动 MySQL、Redis、MinIO、RAG、PTA spider、推荐服务或错误分析服务。

后端容器通过环境变量访问这些外部服务：

- 同一台服务器宿主机上的服务：使用 `host.docker.internal`。
- 同一 Docker Compose 网络里的服务：使用 compose 服务名，例如 `http://rag-service:8001`。
- 其他服务器上的服务：使用对应内网 IP 或域名。

`docker-compose.yml` 已配置：

```yaml
extra_hosts:
  - "host.docker.internal:host-gateway"
```

这让 Linux 服务器上的容器也可以通过 `host.docker.internal` 访问宿主机。

## 首次部署

在服务器项目目录中复制环境变量模板：

```bash
cp .env.example .env
```

然后编辑 `.env`，至少替换这些值：

```env
DB_PASSWORD=replace-me
MINIO_ACCESS_KEY=replace-me
MINIO_SECRET_KEY=replace-me
JWT_SECRET=replace-with-a-random-string-at-least-32-chars
```

如果 MySQL、Redis、MinIO 在服务器宿主机上，默认地址可以保持：

```env
DB_HOST=host.docker.internal
REDIS_HOST=host.docker.internal
MINIO_ENDPOINT=http://host.docker.internal:19000
```

如果某个服务也在同一个 compose 网络中，把地址改成服务名：

```env
RAG_SERVICE_BASE_URL=http://rag-service:8001
RECOMMENDATION_SERVICE_BASE_URL=http://recommendation-service:8003
PTA_SPIDER_URL=http://pta-spider:8100
TAP_ERROR_ANALYSIS_BASE_URL=http://error-analysis-service:8002
```

## 启动

构建并启动后端：

```bash
docker compose --env-file .env up -d --build
```

查看容器状态：

```bash
docker compose ps
```

查看日志：

```bash
docker compose logs -f backend
```

停止服务：

```bash
docker compose down
```

## 健康检查

后端默认映射到服务器 `8081` 端口：

```text
http://服务器IP:8081/actuator/health
```

如果 `.env` 中修改了 `BACKEND_PORT`，访问对应端口。

## 关键环境变量

```env
BACKEND_PORT=8081
SPRING_PROFILES_ACTIVE=prod

DB_HOST=host.docker.internal
DB_PORT=3306
DB_NAME=ptadatabase
DB_USERNAME=root
DB_PASSWORD=replace-me

REDIS_HOST=host.docker.internal
REDIS_PORT=6379

MINIO_ENDPOINT=http://host.docker.internal:19000
MINIO_ACCESS_KEY=replace-me
MINIO_SECRET_KEY=replace-me
MINIO_BUCKET=tap-files

JWT_SECRET=replace-with-a-random-string-at-least-32-chars

RAG_SERVICE_BASE_URL=http://host.docker.internal:8001
PTA_SPIDER_URL=http://host.docker.internal:8100
PTA_SPIDER_AUTO_START=false
RECOMMENDATION_SERVICE_BASE_URL=http://host.docker.internal:8003
TAP_ERROR_ANALYSIS_BASE_URL=http://host.docker.internal:8002
```

生产环境不要提交 `.env`，仓库只保留 `.env.example`。

## 常见问题

### 容器无法访问宿主机服务

确认 `docker-compose.yml` 中存在：

```yaml
extra_hosts:
  - "host.docker.internal:host-gateway"
```

并确认宿主机上的服务监听地址不是仅允许容器外不可达的地址。MySQL、Redis、MinIO 等服务需要允许来自 Docker 网桥网段的连接。

### 启动后健康检查失败

优先查看日志：

```bash
docker compose logs -f backend
```

常见原因：

- MySQL 未启动或账号密码错误。
- Redis 未启动或密码错误。
- MinIO 地址、Access Key、Secret Key 或 bucket 错误。
- `JWT_SECRET` 为空或过短。
- Flyway 迁移失败。

### 需要连接同一 compose 网络里的其他服务

把后端加入对应外部网络，并将 URL 改为服务名。示例：

```yaml
networks:
  default:
    external: true
    name: tap-services
```

然后在 `.env` 中配置：

```env
RAG_SERVICE_BASE_URL=http://rag-service:8001
```
