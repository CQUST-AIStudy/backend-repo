# 后端启动说明

项目目录：

```cmd
D:\AI-study\backend-repo\AI_Ds
```

后端是 Spring Boot + Maven 项目，默认端口：

```text
http://localhost:8081
```

## 依赖环境

本地命令行启动后端前，需要先有这些服务：

```text
MySQL: 127.0.0.1:3306
Redis: 127.0.0.1:6379
MinIO: http://127.0.0.1:19000
```

推荐直接用根目录 Docker Compose 起整套依赖和服务：

```cmd
cd /d D:\AI-study
docker compose up -d --build
```

如果只想跑本地 Java 后端，确保 `local.env.ps1` 存在：

```text
D:\AI-study\backend-repo\AI_Ds\local.env.ps1
```

这个文件只放本机配置和密钥，不要提交。

本地 Java 后端至少先启动基础设施：

```cmd
cd /d D:\AI-study
docker compose up -d mysql redis minio minio-init
```

## 本地命令行启动后端

在 `cmd` 里执行：

```cmd
cd /d D:\AI-study\backend-repo\AI_Ds
start-local.cmd
```

等价的完整命令：

```cmd
cd /d D:\AI-study\backend-repo\AI_Ds
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { if (Test-Path .\local.env.ps1) { . .\local.env.ps1 }; if (-not $env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE='dev' }; .\mvnw.cmd spring-boot:run }"
```

看到类似日志表示启动成功：

```text
Tomcat started on port 8081
Started TeachingAssistantApplication
```

## Docker 启动整套项目

根目录已经有总编排文件：

```text
D:\AI-study\docker-compose.yml
```

启动：

```cmd
cd /d D:\AI-study
docker compose up -d --build
```

或者：

```cmd
cd /d D:\AI-study
start-docker.cmd
```

查看状态：

```cmd
docker compose ps
```

看日志：

```cmd
docker compose logs -f backend
```

停止：

```cmd
docker compose down
```

Docker 方式启动后，后端容器使用这些内部依赖地址：

```text
DB_HOST=mysql
REDIS_HOST=redis
RECOMMENDATION_SERVICE_BASE_URL=http://recommendation-service:8003
PTA_SPIDER_URL=http://pta-spider:8100
MINIO_ENDPOINT=http://minio:9000
MINIO_BUCKET=tap-files
```

这些已经在根目录 `docker-compose.yml` 里配置好了，通常不用手动改。

## 只构建或重启后端容器

只重新构建后端镜像：

```cmd
cd /d D:\AI-study
docker compose build backend
```

只启动后端相关服务：

```cmd
cd /d D:\AI-study
docker compose up -d --build mysql redis minio minio-init recommendation-service pta-spider backend
```

重启后端：

```cmd
cd /d D:\AI-study
docker compose restart backend
```

## 数据库初始化

如果是新的本地库，可以创建空库，让 Flyway 在后端启动时自动迁移：

```sql
DROP DATABASE IF EXISTS ptadatabase;
CREATE DATABASE ptadatabase DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

不要手动逐个执行 `src\main\resources\db\migration` 下的 SQL，后端启动时 Flyway 会自动执行。

## 常见问题

### PowerShell 禁止运行脚本

使用本文命令里的 `-ExecutionPolicy Bypass` 即可绕过当前进程的脚本限制：

```cmd
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { . .\local.env.ps1; .\mvnw.cmd spring-boot:run }"
```

### Docker 构建找不到后端 Dockerfile

后端 Dockerfile 应该在：

```text
D:\AI-study\backend-repo\AI_Ds\Dockerfile
```

根目录 `docker-compose.yml` 的 `backend` 服务会使用这个文件构建镜像。

### Flyway 迁移失败

如果本地数据不重要，重建空库后再启动：

```sql
DROP DATABASE IF EXISTS ptadatabase;
CREATE DATABASE ptadatabase DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### MinIO 报 bucket 不存在

如果日志里有 `The specified bucket does not exist`，说明对象存储桶还没创建。Docker 方式会通过 `minio-init` 自动创建：

```cmd
cd /d D:\AI-study
docker compose up -d minio minio-init
```

控制台地址：

```text
http://localhost:19001
```
