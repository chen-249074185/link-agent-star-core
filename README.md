# 星链智核（LinkAgent）

全域自适应多端 AI 智能助手：Java 单后端应用，微信 + 小程序 + Web 三端接入。

## 核心能力

- 异地求职租房全自动决策工作流：片区就业分析 → 房源筛选 → 通勤成本批量测算 → 风险识别 → 量化对比 → 自动生成租房决策报告
- Agent 工具调用：大模型编排 + MCP 工具接入（RAG 房源检索、爬虫采集、猎聘职位分析、高德通勤、风险检测、加权打分）
- 治理内核（GovernanceKernel）：技能执行前五道安检——技能存在、场景白名单、高危操作确认、单轮步数上限、单日限次
- 技能三层划分：主线配套（MAIN）、场景支线（SCENE）、架构扩展（EXT），主线工作流自动过滤无关工具
- 多端消息接入：微信（wechat-ilink-sdk，多 Bot 管理）、小程序、Web
- 用户体系：JWT 鉴权 + 每用户私有知识库归属校验
- 双运行模式：自由问答（任意原子技能单独调用）与任务工作流（完整需求自动全链路编排）

## 技术栈

- Java 21 + Spring Boot 3.3.13 + Maven 3.9.9
- LLM：DeepSeek（对话 + 工具调用），阿里云 DashScope（文生图、语音、Embedding），统一 LlmClient 接口可切换
- MCP 双轨：官方 Java MCP SDK（客户端接入 + 独立 Server 模块：RAG、Crawl；猎聘官方 CLI 包装）
- 存储：MySQL + MyBatis、Redis、SQLite + sqlite-vec（向量检索）
- 部署：Docker + docker-compose（MySQL / Redis / app / liepin-mcp 四服务一键启动）
- API 文档：SpringDoc OpenAPI

## 项目结构

```
agent/                  Spring Boot 主服务（微信/小程序/Web 三端 + Agent 编排 + 治理内核）
  src/main/java/com/star/agent/
    adapter/wechat/     微信接入：Bot 生命周期、消息适配、会话管理
    workflow/           工作流引擎 + 租房决策编排（串行/循环/分支/容错/状态保存）
    mcp/                MCP 双轨：客户端接入 + 房源采集入库
    skill/              技能契约与实现（天气/通勤/打分/风控等），Mock 占位可切换
    governance/         治理内核：五道安检 + 限流 + 高危确认
    llm/                LLM 客户端统一接口（DeepSeek 实现，流式 + 工具调用）
rag-mcp/                RAG 检索 MCP Server（文档解析、向量化、混合检索）
crawl-mcp/              爬虫采集 MCP Server（链家房源列表解析）
liepin-mcp/             猎聘官方 CLI 包装 MCP Server（就业/职位分析）
mcp/                    第三方 MCP 工具
miniprogram/            微信小程序端
deploy/                 Docker 部署配置
spike/                  原型验证代码
```

## 快速开始

### 本地开发

1. 准备环境：JDK 21、Maven 3.9+、MySQL 8、Redis 7
2. 复制 `.env.example` 为 `.env`，填入 DeepSeek / DashScope / 高德 / 微信等密钥
3. 启动 MySQL 与 Redis，按 `deploy/init.sql` 建表
4. 启动主服务：`cd agent && ./mvnw spring-boot:run`
5. 访问 OpenAPI 文档：`http://localhost:8080/swagger-ui.html`

### Docker 一键部署

```bash
cp .env.example .env   # 填入密钥
docker compose up -d   # 启动 MySQL + Redis + app + liepin-mcp 四服务
```

密钥全部走环境变量（.env），零明文入库。RAG / Crawl MCP 以 jar 形式挂载进应用容器，镜像自包含。

## 测试

476 个测试用例全绿（单元 + 集成），覆盖消息适配、场景识别、工作流编排、治理内核、旅游规划、房源采集等核心链路。
