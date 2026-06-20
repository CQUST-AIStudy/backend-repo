# TAP Backend README

## 1. 项目说明

这是教学辅助系统的后端服务，基于 Spring Boot 3 + Maven 构建，默认本地访问地址：

```text
http://localhost:8081
```

后端主要负责：

- 用户、教师、学生等业务接口
- 题目推荐与 AI 相关能力接入
- 文件存储与对象上传
- 数据库迁移与本地开发初始化

## 2. 技术栈

- Java 17
- Spring Boot 3.4.4
- Maven Wrapper (`mvnw.cmd`)
- MySQL
- Redis
- MinIO
- Flyway
- MyBatis + JPA

部分功能会依赖外部服务，但本文档只关注“后端单独启动”：

- `recommendation-service`
- `pta_spider`
- 可选的 AI / 向量检索相关配置

## 3. 目录位置

项目目录：

```text
D:\AI-study\backend-repo\AI_Ds
```

常用文件：

- `start-local.cmd`：本地启动后端
- `local.env.example.ps1`：本地环境变量模板
- `local.env.ps1`：你自己的本地环境变量，不要提交
- `src\main\resources\application.yml`：通用配置
- `src\main\resources\application-dev.yml`：`dev` 环境补充配置
- `src\main\resources\application-local.yml`：本地联调补充配置

## 4. 启动前准备

### 4.1 必备软件

本地建议先确认这些工具可用：

- JDK 17
- PowerShell 或 CMD

### 4.2 基础依赖服务

后端本地运行至少需要这些基础服务：

- MySQL
- Redis
- MinIO

默认本地地址通常是：

```text
MySQL: 127.0.0.1:3306 或 3307
Redis: 127.0.0.1:6379
MinIO API: http://127.0.0.1:19000
MinIO Console: http://127.0.0.1:19001
```

这些服务需要你先自己启动好，后端本身不会一键帮你拉起。

## 5. 本地环境变量配置

首次启动前，先复制模板文件：

```cmd
cd /d D:\AI-study\backend-repo\AI_Ds
copy local.env.example.ps1 local.env.ps1
```

然后编辑 `local.env.ps1`，至少确认这些变量：

```powershell
$env:DB_PASSWORD = "你的数据库密码"
$env:JWT_SECRET  = "长度至少 32 位的随机字符串"
```

常见本地配置项：

- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USERNAME`
- `DB_PASSWORD`
- `JWT_SECRET`
- `RECOMMENDATION_SERVICE_BASE_URL`
- `MINIO_ENDPOINT`
- `MINIO_ACCESS_KEY`
- `MINIO_SECRET_KEY`

说明：

- `local.env.ps1` 只放本机配置和密钥，不要提交到仓库。
- `start-local.cmd` 会自动加载这个文件。
- 本地开发默认建议使用 `dev` profile。

## 6. 本地启动后端

在项目目录执行：

```cmd
cd /d D:\AI-study\backend-repo\AI_Ds
start-local.cmd
```

这个脚本会自动做几件事：

- 检查 `mvnw.cmd` 是否存在
- 加载 `local.env.ps1`
- 默认启用 `dev` profile
- 临时设置：
  `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false`
  `SPRING_JPA_HIBERNATE_DDL_AUTO=update`
- 执行 `mvnw.cmd spring-boot:run`

如果你想手动执行等价命令，可以用：

```cmd
cd /d D:\AI-study\backend-repo\AI_Ds
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { if (Test-Path .\local.env.ps1) { . .\local.env.ps1 }; if (-not $env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE='dev' }; .\mvnw.cmd spring-boot:run }"
```

启动成功后，日志里通常会看到类似内容：

```text
Tomcat started on port 8081
Started TeachingAssistantApplication
```

## 7. 数据库初始化

如果是全新的本地数据库，可以先创建空库，再让后端启动时由 Flyway 自动迁移：

```sql
DROP DATABASE IF EXISTS ptadatabase;
CREATE DATABASE ptadatabase DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

注意：

- 不要手动逐个执行 `src\main\resources\db\migration` 里的 SQL。
- 正常情况下，Flyway 会在应用启动时自动执行迁移。

## 8. 常见问题

### 10.1 `local.env.ps1` 缺失

如果启动时报错提示缺少 `local.env.ps1`，直接从模板复制：

```cmd
cd /d D:\AI-study\backend-repo\AI_Ds
copy local.env.example.ps1 local.env.ps1
```

然后补上至少这两个值：

- `DB_PASSWORD`
- `JWT_SECRET`

### 10.2 PowerShell 禁止执行脚本

`start-local.cmd` 已经使用了 `-ExecutionPolicy Bypass`，一般可以绕过当前进程的脚本限制。

如果你手动执行命令，也记得带上：

```cmd
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { . .\local.env.ps1; .\mvnw.cmd spring-boot:run }"
```

### 10.3 Flyway 迁移失败

如果本地库里的数据不重要，最简单的处理方式通常是重建空库：

```sql
DROP DATABASE IF EXISTS ptadatabase;
CREATE DATABASE ptadatabase DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

然后重新启动后端。

### 8.4 MinIO bucket 不存在

如果日志里出现：

```text
The specified bucket does not exist
```

说明对象存储桶还没创建，需要先在你自己的 MinIO 里创建对应 bucket。

MinIO 控制台地址：

```text
http://localhost:19001
```

## 9. 推荐启动顺序

如果你是第一次在本地跑这个后端，建议按这个顺序来：

1. 启动 MySQL、Redis、MinIO
2. 复制并填写 `local.env.ps1`
3. 确认数据库 `ptadatabase` 已创建
4. 运行 `start-local.cmd`
5. 看到 `Tomcat started on port 8081` 后再开始联调

## 10. 一句话版本

只想单独把后端跑起来，可以直接照这几步：

```cmd
cd /d D:\AI-study\backend-repo\AI_Ds
copy local.env.example.ps1 local.env.ps1
start-local.cmd
```

前提是你已经先手动启动并确认这些基础服务可用：

- MySQL
- Redis
- MinIO
