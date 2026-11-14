# Phase 1 验收记录

## 已实现范围

工程包含父 POM、7 个 Maven 模块、官方 Maven Wrapper、Spring Boot 启动类、PostgreSQL/Redis/Qdrant 配置、MyBatis-Plus、Flyway、SpringDoc、统一响应、全局异常、健康检查和隔离的集成测试环境。

核心文件：`pom.xml`、`team-mind-web/src/main/resources/application.yml`、`docker-compose.yml`、`docker-compose.test.yml`、`team-mind-web/src/main/resources/db/migration/V1__create_phase1_schema.sql`。

未实现文档上传、RAG、Agent、记忆、登录权限和前端业务页面，符合 Phase 1 停止边界。

## 已执行验证

2026-11-14 使用 Eclipse Temurin Java 21.0.13、Maven 3.9.11，在不含历史 `target` 的临时目录中复制当前项目源码并执行：

```bash
mvn clean package
```

结果为 `BUILD SUCCESS`，7 个 Maven 模块全部打包成功。默认测试共 5 项，全部通过：统一响应 1 项、Spring 应用上下文 1 项、全局异常 3 项。可执行 Spring Boot JAR 已成功生成。

Qdrant 官方客户端 API 已按 1.16.0 Javadoc 核对，使用 `QdrantGrpcClient.newBuilder(...).withTimeout(...)` 和 `QdrantClient.healthCheckAsync(Duration)`，不是虚构客户端方法。

## 尚待本机验收

当前命令执行沙箱没有 Docker，也不允许安装系统服务；独立 Qdrant 二进制下载因网络中断未完成。因此不能宣称 PostgreSQL、Redis、Qdrant 的真实连接、Flyway 实际执行和 Swagger HTTP 访问已在此环境验收通过。

请在安装 Docker 的本机执行以下命令完成剩余验收：

```bash
./scripts/verify-infrastructure.sh
```

脚本启动隔离的三项真实服务，并运行 4 项集成测试：PostgreSQL 查询和 Flyway 重复迁移、Redis PING、Qdrant gRPC 健康检查、真实 HTTP 健康接口与 OpenAPI/Swagger。脚本结束后自动清理测试容器及临时数据。

日常运行验收：

```bash
cp .env.example .env
docker compose up -d --wait
./mvnw -pl team-mind-web -am package -DskipTests
./mvnw -f team-mind-web/pom.xml spring-boot:run -Dspring-boot.run.profiles=local
curl -i http://localhost:8080/api/v1/actuator/health
curl -i http://localhost:8080/api/v1/api-docs
```

只有健康接口中 `db`、`redis` 和 `qdrant` 全部为 `UP`，且 Swagger UI 能访问，Phase 1 才能视为完整验收通过。

## 沙箱文件说明

沙箱挂载目录不允许删除旧文件，因此 `team-mind-web/src/main/java/com/teammind/web/health/QdrantHealthIndicator.java` 已被覆盖为仅含注释的空文件；实际实现位于 `team-mind-observability/src/main/java/com/teammind/observability/infrastructure/QdrantHealthIndicator.java`。在可删除文件的本机工作区可以删除这个空文件及其空目录。

沙箱生成的 `target` 和 `.attach_pid*` 是构建/测试临时产物，不应提交版本控制；相关忽略规则已加入 `.gitignore`。
