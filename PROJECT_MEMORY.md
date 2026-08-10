# 星链智核（LinkAgent 重写）项目记忆文档

> 本文件用于跨会话上下文记忆，每次开发结束请更新。更新日期：2026-08-15

## 一、项目定位

- 项目名：星链智核（LinkAgent 全域自适应多端 AI 智能助手）
- 目标：Java 单后端应用，微信 + 小程序 + Web 三端接入的 AI 助手
- 周期：2026-08-10 启动，11-22 终验，15 周（90 工作日）
- 依据计划书：《LinkAgent 项目执行计划书-每日细化版.pdf》

## 二、技术栈决策（重要）

- **语言/框架**：Java 21 + Spring Boot 3.3.13（从 4.1.0 改回）+ Maven 3.9.9
- **AI 模型（关键决定）**：
  - **对话 + 工具调用（大脑）→ 用 DeepSeek**（deepseek-chat），可切换阿里云
  - **文生图 / 语音 / embedding → 只用阿里云 DashScope**（DeepSeek 不支持）
  - 实现方式：`LlmClient` 统一接口，默认 DeepSeek，可切阿里云。**尚未实现，08-18 才开始**
- **消息接入**：wechat-ilink-sdk 2.3.3（`io.github.lith0924`）
- **存储**：MySQL + MyBatis 3.0.4、Redis、SQLite+sqlite-vec（RAG）
- **部署**：Docker + docker-compose；**文档**：SpringDoc OpenAPI

## 三、项目结构（当前）

```
D:\星链智核\agent\                    ← git 仓库根（外层）
D:\星链智核\agent\agent\              ← Spring Boot 项目（内层，IDEA 打开这层）
  src\main\java\com\star\agent\
    ├── AgentApplication.java
    ├── adapter\wechat\
    │   ├── ILinkConfigFactory.java    ← 连接参数（照搬旧项目）
    │   ├── ILinkLoginVerifier.java    ← 启动入口（main，传 null 走扫码）
    │   ├── SessionStore.java          ← 会话文件存储（sessions/）
    │   ├── ILinkMessageAdapter.java   ← 微信消息→AgentMessage 归一化（convert）
    │   └── BotWorker.java             ← 登录线程 + getUpdates 收消息 + 回显（adapter+sendText）
    ├── message\
    │   ├── AgentMessage.java          ← 消息 DTO（record，6字段）
    │   ├── MessageSource.java         ← 枚举 WECHAT/MINI_PROGRAM/WEB
    │   └── MediaType.java             ← 枚举 TEXT/IMAGE/VOICE/FILE
    ├── infra\
    │   ├── config\AppConfig.java      ← 环境变量读取（@Component）
    │   └── web\Result.java / BizException.java  ← 统一响应/异常
    ├── agent\   skill\   （空，后续用）
  src\main\resources\logback-spring.xml  ← [%X{traceId}] 占位
  src\test\java\com\star\agent\
    ├── AgentApplicationTests.java
    └── message\AgentMessageTest.java  ← DTO 单测（通过）
  pom.xml                                ← Spring Boot 3.3.13 + 依赖
  .env.example                           ← DASHSCOPE_API_KEY / ADMIN_TOKEN（空模板）
```

## 四、已完成进度（对照计划书）

| 日期 | 任务 | 状态 |
|---|---|---|
| 08-10 | 工程创建、包分层、git、.env.example | ✅ |
| 08-11 | AppConfig、logback traceId 占位、Result+BizException | ✅ |
| 08-12 | AgentMessage DTO + MessageSource/MediaType 枚举 + 单测 | ✅ |
| 08-13 | wechat-ilink-sdk 依赖、ILinkConfigFactory、扫码登录验证 | ✅ |
| 08-14 | BotWorker 线程封装 + SessionStore 会话恢复v0 + getUpdates 收消息 | ✅ |
| 08-15 | ILinkMessageAdapter 消息归一化（微信→AgentMessage）+ 回声测试（收→译→回显） | ✅ |

**已提交**：`fe13257`、`e02c21d`（08-15 改动尚未提交）。

## 五、关键代码知识点（已学）

- `@Component` = Spring 管理对象，可注入
- `record` = 自动生成构造器+取值方法，不可变（无 setter）
- 枚举 = 定死选项（MessageSource/MediaType）
- MDC + traceId = 日志链路追踪（`[%X{traceId}]` 已占位，M6 实现）
- DTO（AgentMessage）= 输入侧给系统/LLM；Result = 输出侧给前端/用户
- Result = 统一返回格式 {code,msg,data}；BizException = 内部报错，M6 全局处理器转 Result
- 会话恢复：文件版 SessionStore（M0 简化），M4 升级数据库版

## 六、遇到的坑（教训）

1. **IDEA 打开层级**：必须打开 `D:\星链智核\agent\agent`（内层），不是外层。曾因打开外层导致"看不到 src"、test 依赖报错。解决：删 `.idea` 和 `agent.iml` 重开
2. **Spring Boot 版本**：start.spring.io 默认生成 4.x，依赖名不同（starter-webmvc），已改回 3.3.13
3. **守护线程**：setDaemon(true) 的主线程结束后会退出，需 `Thread.currentThread().join()` 保持运行
4. **二维码过期**：qrcode expired，需尽快扫码；重试机制后续加
5. **sessions/ 文件泄露**：微信 token 存文件，已加入 .gitignore，勿提交
6. **会话多文件**：传 null 每次扫码生成新 botId → 多文件。M4 数据库 upsert 解决，暂不处理
7. **resume 未用 resumeClient**：BotWorker.login() 恢复成功时创建的 resumeClient 直接 return，loop() 仍用未登录的 client → 回显会报未登录。**已修复**（08-15）：加 `this.client = resumeClient` + client 字段去 final
8. **15s 收消息延迟（SDK 机制，非代码 bug）**：getUpdates 是服务端长轮询，微信服务器攒批固定周期(~15s)才返回。两条同时发的消息会一起到达。**不是代码问题**（已排除重试/网络/代理）。影响：流式/打字机效果受此限制，W10 双线程管线再优化

## 七、明天任务（08-16 周日）

**周复盘（1h）**：回顾 W1（08-10~15），更新记忆文档，为 08-17 场景识别做准备。

后续（W2）：
- 08-17 一：Scene枚举(CHAT/CREATION/OFFICE/DATA/GROUP) + SceneDetector v0(关键词规则YAML可配) + 单测
- 08-18 二：DashScope Function Calling + 引入LlmClient统一接口接DeepSeek（对话+工具调用走DeepSeek，文生图/语音/embedding走阿里云）

## 八、后续里程碑（简）

- 08-17~22 W2：场景识别 + 首个 Skill + 流式（08-18 Function Calling 引入 LlmClient，接 DeepSeek）
- 08-24~09-06 W3~W4：Skill 框架（@Component 注册/Composer）
- 09-07~09-20 W5~W6：场景自适应引擎 + 记忆系统（Redis）
- 之后：RAG（W7~W9）、微信管线重写（W10~W11）、Web+小程序（W12~W13）、工程化（W14）

## 九、完整每日计划（08-10 ~ 11-22，对照计划书）

### 阶段 0：最小闭环（W1~W2：08-10 ~ 08-23）
**阶段目标**：微信发一条消息 → 识别场景 → 调一个最简 Skill → 返回结果。不做记忆/RAG/爬虫。

**W1（08-10 ~ 08-15）：工程骨架 + 消息接入**（08-10~14 已完成）
- 08-10 一：Maven工程(Java21+SB3.3.x)/包分层/git/.env.example ✅
- 08-11 二：配置体系(AppConfig System.getenv)/logback traceId占位/统一响应体异常基类 ✅
- 08-12 三：AgentMessage DTO + MessageSource枚举 + 单测 ✅
- 08-13 四：wechat-ilink-sdk依赖/ILinkConfig/扫码登录 ✅
- 08-14 五：BotWorker登录线程/会话恢复v0/getUpdates收消息 ✅
- 08-15 六：**ILinkMessageAdapter(微信→AgentMessage) + 回声测试** ← 明天
- 08-16 日：周复盘(1h)

**W2（08-17 ~ 08-22）：场景识别 + 首个 Skill + 流式**
- 08-17 一：Scene枚举(CHAT/CREATION/OFFICE/DATA/GROUP) + SceneDetector v0(关键词规则YAML可配) + 单测
- 08-18 二：DashScope Function Calling基础(FunctionDefinition+tools) + 解析tool_calls；**引入LlmClient统一接口，接DeepSeek**（自定义调整）
- 08-19 三：第一个Skill(WeatherSkill迁移心知天气) + ToolDispatcher Map路由 + chat主流程(tool_calls→执行→回填→再调模型)
- 08-20 四：DashScope流式API(streamCallMode) + LlmClient.stream()逐token回调
- 08-21 五：Web SSE端点/api/chat/stream(SseEmitter) + 极简测试页面(输入框+EventSource)
- 08-22 六：端到端串联(微信→场景→Skill→模型→回复) + 全链路日志 + 录M0演示视频
- 08-23 日：M0里程碑验收日

阶段DoD：微信发"画一只猫"识别创作、"你好"识别闲聊；浏览器SSE打字机可用；日志可追踪
阶段风险：ILink登录态不稳→复用旧项目resume；DashScope流式不熟→D10预留半天

### 阶段 1：Skill 核心框架（W3~W4：08-24 ~ 09-06）
**阶段目标**：自研可插拔技能框架(@Component自动注册、SkillMeta内聚Schema、SkillComposer组合器、原子/业务分层)。

**W3（08-24 ~ 08-29）：框架核心**
- 08-24 一：Skill接口设计(SkillMeta meta(); SkillResult execute(SkillContext)) + SkillContext(userId/args/media/traceId)/SkillResult评审冻结
- 08-25 二：SkillMeta record(name/description/parameters/riskLevel/scenes[]) + JSON Schema构建工具类
- 08-26 三：SkillRegistry(通过ObjectProvider<Skill>自动收集@Compoent) + 启动构建FunctionDefinition列表打日志 + 重名校验
- 08-27 四：SkillDispatcher(Map路由+统一异常捕获) + 替换阶段0硬编码 + 工具步数上限(单轮5步)
- 08-28 五：迁移3个原子技能@Component化(Weather/Translate/Calculate) + 删阶段0硬编码Skill
- 08-29 六：Skill框架单测(注册/分发/异常/限步) + 验收演示(现场写DemoSkill加类+注解重启即用)
- 08-30 日：周复盘

**W4（08-31 ~ 09-05）：组合器 + 分层**
- 08-31 一：SkillComposer接口设计(invoke(skillName,args,ctx)+深度参数) + 设计评审(嵌套上限/循环检测/异常传播)
- 09-01 二：Composer实现(maxDepth=2硬限制) + 调用栈Set循环检测(A→B→A熔断) + traceId传递
- 09-02 三：编排实战(TripPlanSkill业务层通过Composer调WeatherSkill) + 验证深度限制
- 09-03 四：包结构分层(skill/atomic vs skill/business) + 再迁移3原子技能(Time/Constellation/Lishi)
- 09-04 五：场景过滤技能(SkillMeta.scenes与场景引擎联动，群聊剔HIGH_RISK) + 单测
- 09-05 六：skill/meta目录放技能介绍md(仅后台展示) + M1验收演示 + 框架使用文档
- 09-06 日：M1里程碑验收日

阶段DoD：新增技能=写类+@Component；Composer两级编排防循环；场景过滤高危技能

### 阶段 2：Agent 核心引擎（W5~W6：09-07 ~ 09-20）
**阶段目标**：场景自适应引擎(三层识别) + 模型路由 + SSE完善 + 瞬时/短期记忆。

**W5（09-07 ~ 09-12）：场景自适应引擎**
- 09-07 一：ScenePolicy YAML配置(5场景×{模型,工具白名单,记忆策略,回复风格}) + 配置类绑定 + 启动校验
- 09-08 二：LLM意图分类(qwen-turbo对最近3条消息分类，JSON结构化输出) + 置信度<0.7回退闲聊
- 09-09 三：三层识别串联(关键词快筛→LLM兜底→/scene:work手动覆盖) + 场景切换事件日志
- 09-10 四：LlmClient多模型路由(turbo闲聊/plus办公数据/max创作按ScenePolicy) + 流式非流式统一封装
- 09-11 五：场景引擎集成测试(各场景消息断言模型/工具集/风格切换) + 修复
- 09-12 六：关键词表扩充调优 + 意图分类prompt迭代(few-shot)
- 09-13 日：周复盘

**W6（09-14 ~ 09-19）：记忆系统 + SSE完善**
- 09-14 一：Redis环境(Docker或本地) + spring-data-redis集成 + Key规范(linkagent:{module}:{type}:{id})
- 09-15 二：瞬时记忆(会话上下文Redis，TTL30分钟) + 轻量化压缩(超10轮保留最近+摘要)
- 09-16 三：短期记忆(MySQL conversation表+MyBatis) + Redis+MySQL双写(Redis读异步落库)
- 09-17 四：每日压缩定时任务(00:30 LLM总结当日摘要) + 无效闲聊遗忘规则
- 09-18 五：SSE完善(SseEmitter生命周期管理) + 流式中途终止/api/chat/stop
- 09-19 六：M2综合验收(跨天对话连贯+场景切换+打字机流式三合一) + 录屏
- 09-20 日：M2里程碑验收日

阶段DoD：三层识别准确率≥90%；瞬时/短期记忆生效；SSE打字机稳定可中断

### 阶段 3：向量 RAG + 爬虫 Skill（W7~W9：09-21 ~ 10-11）
**阶段目标**：SQLite+sqlite-vec向量检索、文档上传→切片→向量化→问答、爬虫风控重构、5精品业务技能迁移。

**关键决策点D37**：sqlite-vec在Windows+sqlite-jdbc加载先做半天spike；不通切方案B(内存余弦相似度，VectorStore接口不变)

**W7（09-21 ~ 09-26）：向量存储引擎**
- 09-21 一：上午sqlite-vec扩展加载spike(sqlite-jdbc+vec0虚拟表) + 下午定方案 + VectorStore接口设计(upsert/search/delete by kbId)
- 09-22 二：SQLite表结构(chunks表id/kb_id/content/embedding/metadata_json/created_at) + 建表脚本+连接池
- 09-23 三：KNN查询(vec_distance_cosine) + topK+相似度阈值 + 性能基准(1000条<100ms)
- 09-24 四：DashScope text-embedding-v2客户端封装 + 批量向量化(batch上限+失败重试)
- 09-25 五：文档解析(PDFBox解析PDF/TXT/MD直读) + 内容清洗
- 09-26 六：智能切片(段落切+512token定长兜底+overlap) + 切片单测
- 09-27 日：周复盘

**W8（09-28 ~ 10-03）：RAG 链路 + 安全**
- 09-28 一：上传管道(上传→解析→切片→向量化→入库异步+进度状态) + 失败回滚(清理chunk)
- 09-29 二：多知识库隔离(knowledge_base_id) + CRUD服务 + 初始化三库(租房/个人文档/案例)
- 09-30 三：RagSkill(注册为Skill，检索topK→拼prompt→LLM总结标注来源)
- 10-01 四：上传安全加固(类型白名单pdf/txt/md、大小10MB、文件名清洗防路径穿越) + 安全单测(../攻击)
- 10-02 五：RAG管理接口/api/rag/upload|list|delete|search + 错误码规范
- 10-03 六：RAG阶段验收(上传→问答→来源引用演示) + 检索质量调优
- 10-04 日：周复盘(国庆自行调剂)

**W9（10-05 ~ 10-10）：爬虫风控 + 业务技能迁移**
- 10-05 一：AdvancedCrawlService迁移重构(UA池轮换/域名限速300ms/指数退避/正文密度/内嵌JSON)
- 10-06 二：爬虫风控(工具风险分级HIGH_RISK/单日上限Redis计数/内网高危域名黑名单)
- 10-07 三：CrawlSkill注册(auto/static/json/js四模式) + 群聊场景自动禁用
- 10-08 四：业务技能迁移1 TripPlanSkill(高德POI+导航+预算) + 2 FatLossSkill(BMR+天气联动+AI食谱)
- 10-09 五：迁移3 CreativeDirectorSkill(cases.json检索) + 4 RedesignPageSkill(网页改版) + 5 DataReportSkill(搜索爬取分析报告)
- 10-10 六：M3综合验收(RAG问答+爬虫抓取+5技能回归) + 录屏
- 10-11 日：M3里程碑验收日

### 阶段 4：微信 Bot 管线重写（W10~W11：10-12 ~ 10-25）
**阶段目标**：MessageAdapter抽象、双线程管线、状态机、防抖、多Bot、会话恢复、全模态、分片模拟流式。

**W10（10-12 ~ 10-17）：管线核心组件**
- 10-12 一：MessageAdapter接口(receive/send/sendImage/sendFile) + ILinkMessageAdapter重构对接 + 企微预留文档
- 10-13 二：MessageDispatcher重写(fetcher长轮询线程+processor 200ms轮询) + seenMsgIds去重 + 会话失效自动重登
- 10-14 三：状态机5态(IDLE/等图/等约束/等二图/双图操作)+迁移矩阵 + 双图8s超时 + 停止/重开/取消命令
- 10-15 四：DebounceProcessor(图片语音3s/纯文字1.5s) + PendingMessage多段聚合 + "否/直接处理"立即触发
- 10-16 五：多媒体预处理(图片下载→base64) + 语音ASR(魔数检测5格式+ffmpeg转16k WAV，失败降级)
- 10-17 六：ReplySender发送优先级(语音>图片>内嵌地图/二维码>文本) + 图片URL失败降级纯文本
- 10-18 日：周复盘

**W11（10-19 ~ 10-24）：多 Bot + 分片流式 + 稳定性**
- 10-19 一：BotRegistry多账号注册表 + wechat_session表+MyBatis DAO(bot_token/user_id/bot_id/saved_at) ← 会话恢复数据库版
- 10-20 二：启动恢复(scanAllBotIds扫表逐个resume) + 7天过期清理 + 恢复失败转扫码
- 10-21 三：#newbot/#logout命令 + 多Bot并行 + per-user隔离验证(两账号不串音)
- 10-22 四：微信分片模拟流式(200字缓冲分片编辑式追加) + 长消息截断保护
- 10-23 五：全模态回归(文生图/图片识别/语音合成/音频分析在新管线验证) + 修复
- 10-24 六：72小时稳定性测试 + 内存监控(jcmd GC日志/堆基线) + M4预验收
- 10-25 日：M4里程碑验收日(检查24h数据：内存曲线/无崩溃/无串音)

阶段DoD：多Bot免扫码恢复；防抖聚合正确；全模态可用；72h稳定无泄漏

### 阶段 5：Web 前端 + 小程序（W12~W13：10-26 ~ 11-08）
**阶段目标**：三端齐备——统一后端API、Admin鉴权、Web三页重写、微信小程序(复用后端API)。

**W12（10-26 ~ 10-31）：后端API + Web重写**
- 10-26 一：统一REST API梳理补齐(/api/chat/stream、/api/rag/*、/api/skill/*、/api/memory/*) + 接口文档注释
- 10-27 二：Admin鉴权(Filter+环境变量ADMIN_TOKEN) + /api/admin/*全保护 + 401统一响应 ← ADMIN_TOKEN在这里才用
- 10-28 三：chat.html重写(保留赛博朋克视觉+Token监控+人工接管) + JS全换(EventSource SSE打字机+mock兜底)
- 10-29 四：manage.html重写上(RAG知识库管理Tab+技能开关Tab)
- 10-30 五：manage.html重写下(调用统计Tab Token/技能次数 + 日志审计Tab traceId查询)
- 10-31 六：login.html适配(保留星空粒子特效，对接qrcode/status接口) + Web全流程联调
- 11-01 日：周复盘

**W13（11-02 ~ 11-07）：微信小程序**
- 11-02 一：小程序工程初始化(原生) + 会话管理(userId绑定与微信Bot互通) + 页面框架(对话/知识库/记忆/我的)
- 11-03 二：对话页(wx.request enableChunked流式，不行降级500ms轮询) + 消息气泡UI
- 11-04 三：文档上传页(选文件→/api/rag/upload→进度) + 记忆管理页(查看/清除/归档列表)
- 11-05 四：人设切换页(PersonaManager) + 历史记录页(分页)
- 11-06 五：三端联调(同一问题微信/Web/小程序三端提问会话互通验证) + 修复
- 11-07 六：M5验收(三段视频：微信+Web+小程序)
- 11-08 日：M5里程碑验收日

阶段风险：小程序SSE兼容→备降级轮询；审核资质→以开发者工具演示为准

### 阶段 6：工程化打磨（W14：11-09 ~ 11-15）
**阶段目标**：traceId链路、全局异常、OpenAPI、Docker一键部署、集成测试、归档记忆、README。
- 11-09 一：traceId(Spring Filter生成UUID放MDC + logback启用[%X{traceId}]) + 验证贯穿全链路 ← logback占位生效
- 11-10 二：全局异常@ControllerAdvice(统一{code,msg,traceId}) + 清理吞异常 + 完整日志栈
- 11-11 三：SpringDoc OpenAPI(依赖+配置+分组chat/admin/rag) + 补Controller注解
- 11-12 四：Dockerfile多阶段(mvn package→JRE+jar) + docker-compose.yml(MySQL+Redis+app三服务，密钥走environment)
- 11-13 五：核心链路集成测试(Mockito mock DashScope，消息进→场景→技能→模型→返回) + 工具类单测补齐
- 11-14 六：永久归档记忆(触发词记住/重要/别忘了+手动归档接口) + README完整编写 + M6验收
- 11-15 日：M6里程碑验收日(全新环境docker-compose up复验)

### 缓冲与终验（W15：11-16 ~ 11-22）
- 11-16 一：缓冲池消化延期项(无延期做体验优化；可选0.5天Python跑LangGraph最小Demo对比学习，不集成)
- 11-17 二：全量回归测试 + 性能基线(延迟/Token/内存) + 回归报告
- 11-18 三：录制最终演示视频(三端+RAG+技能编排+管理后台，3分钟) + 旁白脚本
- 11-19 四：答辩Q&A文档20个高频问题(场景识别为什么两层/sqlite-vec为什么不用MySQL/traceId怎么实现/记忆怎么压缩/风控怎么做/为什么不用LangGraph) + 自问自答
- 11-20 五：简历项目描述定稿(策划书第10节模板) + GitHub仓库整理(README徽章/演示GIF/目录说明)
- 11-21 六：全流程彩排(从docker-compose up完整走一遍) + 模拟答辩
- 11-22 日：M7终验日(对照交付物清单逐项勾选，正式交付)

### 终验交付物清单（11-22）
代码仓库(git历史清晰零明文) / 一键部署(docker-compose up) / README(简介架构图本地开发Docker API入口) / API文档(Swagger可调试) / 测试(核心链路集成+mvn test全绿) / 演示材料(三端+3分钟总视频) / 答辩材料(Q&A 20问+简历定稿)

### 风险登记册
- R1 sqlite-vec Windows加载失败(中/高)：09-21上午spike定生死，方案B内存余弦兜底 D37
- R2 ILink SDK协议变动/封号(中/高)：测试小号开发；Web+小程序不依赖微信随时可演示
- R3 DashScope日配额耗尽(高/中)：集成测试全Mock；真实调用限流；多Key轮换；验收前预留额度
- R4 流式+Function Calling复杂度超预期(中/中)：阶段0流式只做纯文本；工具调用先整段返回W4后合流 D10
- R5 单人开发卡壳连锁延期(中/高)：每阶段DoD可裁剪；缓冲周吸收；阻塞超半天必降级
- R6 小程序SSE兼容(中/低)：降级分片轮询 D74
- R7 ffmpeg缺失ASR失败(低/低)：转码失败用原文件；README声明依赖 D56

### PM 特别约束
- 前两周必须有可演示物(M0哪怕最简对话)
- 模块顺序不可乱：核心引擎→知识库→端→工程化；小程序押后
- 旧项目只搬精华：多Bot/防抖/反爬/精品UI/优质技能；不搬B站/商城/明文密钥/巨型类/半成品
- 永久废弃不复活：nuka-store商城、merchant.html、game.html音游
- 高级功能二期：流式断点续输出、复杂归档触发、企业微信实现
- 每个技术选型必须能讲清"为什么"(答辩核心考点)
