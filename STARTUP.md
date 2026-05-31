# 后端启动说明

项目目录：

```cmd
D:\AI-study\backend-repo\AI_Ds
```

后端是 Spring Boot 项目，默认端口：

```text
http://localhost:8081
```

## 依赖环境

需要先启动：

```text
MySQL: 127.0.0.1:3306
Redis: 127.0.0.1:6379
```

本地环境变量文件：

```text
D:\AI-study\backend-repo\AI_Ds\local.env.ps1
```

当前数据库配置：

```text
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=ptadatabase
DB_USERNAME=root
DB_PASSWORD=gege5211314
```

## 首次初始化数据库

如果是本地开发库，建议使用空数据库，让 Flyway 自动建表：

```sql
DROP DATABASE IF EXISTS ptadatabase;
CREATE DATABASE ptadatabase DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

不要手动逐个执行 `src\main\resources\db\migration` 下的 SQL，后端启动时 Flyway 会自动执行。

## 启动命令

在 `cmd` 中执行：

```cmd
cd /d D:\AI-study\backend-repo\AI_Ds
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { . .\local.env.ps1; .\mvnw.cmd spring-boot:run }"
```

看到下面日志表示启动成功：

```text
Tomcat started on port 8081
Started TeachingAssistantApplication
```

## 常见问题

### 找不到 `local.env.ps1`

说明没有进入后端目录。必须使用：

```cmd
cd /d D:\AI-study\backend-repo\AI_Ds
```

### PowerShell 禁止运行脚本

使用带 `ExecutionPolicy Bypass` 的启动命令即可：

```cmd
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { . .\local.env.ps1; .\mvnw.cmd spring-boot:run }"
```

### Flyway 迁移失败

如果本地数据不重要，重建空库后重新启动：

```sql
DROP DATABASE IF EXISTS ptadatabase;
CREATE DATABASE ptadatabase DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

