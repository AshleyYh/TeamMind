# Phase 1 架构说明

## 设计目标与边界

Phase 1 建立可运行、可测试、可继续扩展的模块化单体。当前只实现工程骨架、统一协议、持久化与缓存基础设施、数据库迁移、健康检查和接口文档，不提前实现 RAG、Agent、Memory、用户登录或知识库 CRUD。

采用单体而非微服务，是因为第一阶段首要目标是明确业务边界和核心流程，避免分布式部署成本。模块边界先于服务边界建立，后续确有独立扩缩容需求时再拆服务。

## 模块职责与依赖

```text
team-mind-web
  -> team-mind-ai
       -> team-mind-system       -> team-mind-common
       -> team-mind-knowledge    -> team-mind-common
       -> team-mind-observability -> team-mind-common
  -> team-mind-evaluation
       -> team-mind-ai
```

`common` 只放统一响应、错误码和通用异常，不依赖业务模块。`system` 负责后续用户与权限，`knowledge` 负责文档、分块及入库，`ai` 编排 RAG、Agent、Memory 和工具，`observability` 负责 Trace、Evidence 以及基础设施健康指标，`evaluation` 负责离线评测，`web` 是唯一的启动与 HTTP 入口。

当前依赖由 Maven 显式声明，不存在循环依赖。`web` 只组合业务模块，不应沉淀领域规则。`ai` 和 `evaluation` 不直接提供 Controller。各业务模块后续使用 `controller`（仅 Web 层）、`application`、`domain`、`infrastructure` 分包，Mapper 统一放在 `mapper` 包下，由启动类扫描。

`team-mind-ui` 是独立的 Vue 3 工作区，不属于 Maven reactor，Phase 1 不生成前端页面。

## 启动链路

```text
HTTP / Actuator / OpenAPI
        |
team-mind-web
  |-- Spring Boot + Jakarta Validation
  |-- GlobalExceptionHandler -> ApiResponse / ErrorCode
  |-- MyBatis-Plus + PostgreSQL + HikariCP
  |-- Flyway -> V1__create_phase1_schema.sql
  |-- Spring Data Redis
  |-- QdrantGrpcClient Bean
        |
team-mind-observability
  `-- QdrantHealthIndicator -> Qdrant healthCheck RPC
```

PostgreSQL 连接池启动失败会阻止应用启动。Redis 与 Qdrant 使用健康指标报告可用性；不健康的依赖通过 readiness 暴露，应用本身仍可启动，便于容器编排系统识别。Qdrant 健康检查有 3 秒默认超时，普通客户端请求有独立的 10 秒默认超时；异常信息只返回异常类型，不返回凭据或远程报文。

业务 API 使用 `ApiResponse` 包装，成功码为 `0`。参数校验、JSON 格式、业务异常和未知异常分别映射到稳定的 HTTP 状态与错误码。Actuator 与 SpringDoc 保留各自标准协议，不强行套用业务响应包装。

## 数据与迁移

Flyway 是应用启动时的唯一 DDL 执行者，迁移位置为 `team-mind-web/src/main/resources/db/migration`。`V1` 创建需求文档中的 9 张核心表，并增加必要外键、状态检查、唯一约束、查询索引、逻辑删除和审计字段。向量 ID 与 PostgreSQL Chunk 通过 `knowledge_chunk.vector_id` 关联。

手工初始化入口为 `infra/db/init.sql`，通过 `psql` 的相对路径包含执行同一 `V1` 文件，不维护两份容易漂移的 DDL：

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f infra/db/init.sql
```

手工入口只用于新数据库，不能重复执行，也不会自动写入 Flyway 历史。如果采用手工初始化，之后需要自行执行 `flyway baseline` 或保持应用迁移关闭。推荐直接使用应用自动迁移，避免手工基线管理。已应用过的迁移文件不得原地修改，后续变更必须新增 `V2`、`V3` 等迁移。

当前模型尚未实现权限关联表和消息表，因为对应功能不在 Phase 1。用户、知识库权限和会话消息会在各自业务阶段补齐，不在初始化阶段虚构业务逻辑。

## 环境与安全

配置集中在 `application.yml`，支持环境变量覆盖。`.env.example` 仅提供本地示例，`.env` 被 Git 忽略。数据库密码、Qdrant API Key、Redis 密码均不写入 Java 代码。

`local` profile 显示健康详情，默认环境隐藏详情。开发环境仅暴露 `health` 和 `info`。生产部署需要限制 Actuator 和 Swagger 的访问，不应直接暴露到公网。上传大小限制只是基础配置，实际文件类型校验属于 Phase 2。

日常 Compose 使用命名数据卷，测试 Compose 使用独立项目名、独立端口和临时存储。测试脚本退出时清理测试资源，不影响日常开发数据。
