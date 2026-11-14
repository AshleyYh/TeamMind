# TeamMind

企业级研发知识库 Agent 平台。当前完成 **Phase 1：架构设计与工程初始化**，尚未实现文档上传、RAG、Agent、记忆或前端业务页面。

详细设计见 [架构说明](docs/architecture.md)，阶段验收见 [Phase 1 验收记录](docs/phase1-verification.md)，原始需求见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 技术基线

Java 21、Spring Boot 3.5.7、Maven 3.9.11、PostgreSQL 16、Redis 7、Qdrant 1.16.0、MyBatis-Plus 3.5.14、Flyway、SpringDoc OpenAPI 2.8.14。Maven Wrapper 已包含在仓库中，本机只需安装 JDK 21 和 Docker。

## 本地启动

在项目根目录执行：

```bash
cp .env.example .env
# 按需修改 .env，尤其是数据库密码。
docker compose up -d --wait
./mvnw -pl team-mind-web -am package -DskipTests
./mvnw -f team-mind-web/pom.xml spring-boot:run -Dspring-boot.run.profiles=local
```

`local` profile 仅用于本地开发，会展示健康详情。其他环境默认隐藏健康详情，生产环境还应限制 Actuator 和 OpenAPI 的网络访问，并按需关闭接口文档。

也可以先打包，再运行可执行 JAR：

```bash
./mvnw clean package
java -jar team-mind-web/target/team-mind-web-1.0.0-SNAPSHOT.jar --spring.profiles.active=local
```

Windows 使用 `mvnw.cmd` 代替 `./mvnw`。

## 访问与验收

应用默认监听 `8080`，启动时由 Flyway 自动创建数据库表。

```bash
curl -i http://localhost:8080/api/v1/actuator/health
curl -i http://localhost:8080/api/v1/actuator/health/readiness
curl -s http://localhost:8080/api/v1/api-docs
```

健康接口返回 `status: UP`，且 `db`、`redis`、`qdrant` 均为 `UP`，才表示三项基础设施连接验收通过。Swagger UI 地址为 <http://localhost:8080/api/v1/swagger-ui.html>。

默认测试不需要外部服务：

```bash
./mvnw test
```

真实服务集成测试使用独立端口和临时存储，结束后自动清理：

```bash
./scripts/verify-infrastructure.sh
```

需要 Docker Compose v2.11 或更新版本（支持 `up --wait`）。该脚本会启动 PostgreSQL `15432`、Redis `16379`、Qdrant `16333/16334`，执行真实 SQL、Redis PING、Qdrant gRPC 健康检查以及 OpenAPI HTTP 验证。它不会复用或删除日常开发数据卷。

## 配置

数据库、Redis 和 Qdrant 均通过环境变量覆盖默认值，不将生产密钥写入仓库。`.env` 仅供 Docker Compose 读取；直接启动 Java 进程时，需要自行导出同名环境变量。

主要变量为 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`QDRANT_HOST`、`QDRANT_GRPC_PORT`、`QDRANT_API_KEY`、`QDRANT_USE_TLS` 和 `SERVER_PORT`。完整默认值见 [application.yml](team-mind-web/src/main/resources/application.yml)。

停止本地基础设施：

```bash
docker compose down
```

只有明确需要删除本地开发数据时，才额外添加 `--volumes`。
