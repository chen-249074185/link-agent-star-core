# 星链智核 · 框架使用文档（W4 阶段）

> 配套 M1 验收演示。面向开发者：如何新增技能、双模式怎么工作、Mock/真实如何切换。

## 一、双运行模式

### 1. 自由问答模式
- 入口：微信消息（BotWorker）或 `/api/chat/stream?message=xxx`（浏览器 SSE）
- 流程：消息 → 场景识别 → DeepSeek 工具循环（模型决定调哪个技能）→ 技能执行 → 回复
- 适用：任意原子技能单独调用（天气/通勤/打分/风险检测……）

### 2. 任务工作流模式
- 触发：消息**同时包含** 城市 + 预算 + 通勤上限（如"在北京租房，预算6000，通勤30分钟内"）
- 解析：`RentRequestDetector` 正则抓三要素 → 命中则 `RentDecisionWorkflow.execute()` 直跑流水线（**跳过 LLM**）
- 流程：猎聘就业分析 → RAG 房源筛选 → 高德通勤测算 → 风险检测 → 加权打分 → 生成《租房决策报告》
- 特性：EXT 技能（天气等通用工具）被 Workflow 拦截，无关工具不会混入主线

## 二、技能三层划分

| 类型 | 用途 | 代表 | 是否进 Workflow |
|---|---|---|---|
| MAIN | 租房主线配套 | rag_search / crawl_listings / liepin_jobs / commute_estimate / house_score / rent_risk | ✅ |
| SCENE | 租房支线 | uu_quote | 可选 |
| EXT | 架构验证通用 | get_weather | ❌ 拦截 |

## 三、新增一个技能（三步）

1. 写实现类：实现 `Skill` 接口（`meta()` 自报家门 + `execute()` 接单干活），放 `skill/impl/` 包
2. 加 `@Component` 注解（自动进 SkillRegistry 技能菜单）；Mock 占位技能额外加 `@ConditionalOnProperty(name="linkagent.workflow.mock-enabled", havingValue="true")`
3. 启动日志确认"技能清单(共N个)"出现新技能名

组合技能（内部调其他技能）：`execute()` 里调 `SkillComposer.compose(name, args, ctx, depth, chain)`（深度上限 2，循环熔断）。

## 四、Mock / 真实切换

- 配置项：`linkagent.workflow.mock-enabled`（application.properties）
- `true`：4 个 Mock 占位技能生效（假数据），Workflow 全链路可跑（当前阶段默认）
- `false`：Mock 自动退役，让位真实 MCP（W7~W9 落地后切换），**Workflow 编排逻辑零改动**
- 真实技能不受开关影响：天气（心知）、通勤（高德 AMAP_KEY）随时真实可用

## 五、双轨架构（本地 Skill + MCP）

- 本地轨：`Skill` 接口实现（skill/impl/）
- MCP 轨：MCP server（stdio）→ McpClient 握手（initialize/listTools/callTool）→ 转带前缀 Skill 进 SkillRegistry
- 两轨统一过治理内核五道关（存在/场景/高危确认/限步/限次）

## 六、常用验证命令

```bash
mvn clean test                                    # 全量回归
mvn test -Dtest=RentDecisionWorkflowTest         # 工作流单测
mvn test -Dtest=McpClientHandshakeTest           # MCP 握手（拉起真实子进程）
```