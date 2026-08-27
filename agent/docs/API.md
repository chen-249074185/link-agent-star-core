# 星链智核 REST API 文档（W12 统一梳理）

> 统一响应格式：`{code, msg, data}`，`code=0` 成功，非 0 失败（msg 可直接展示给用户）。
> 鉴权（W12 10-27 已启用）：
> - 用户端：`/api/**`（除登录注册/采集白名单）必须带 `Authorization: Bearer <JWT>`，401 未登录；token 的 userId 与请求参数 userId 不一致 → 403（R10）
> - SSE 特例：`EventSource` 不能带请求头，token 走 query 参数 `?token=xxx` 兜底
> - 管理端：`/api/admin/**` 必须带 `X-Admin-Token: <ADMIN_TOKEN>`，不一致 401

## 一、用户认证 `/api/user`（W12 新增）

| 方法 | 路径 | 入参 | 成功 data |
|---|---|---|---|
| POST | /api/user/send-code | `{"email"}` | null |
| POST | /api/user/register | `{"type":"username","username","password"}` 或 `{"type":"email","email","code"}` | 邮箱路径 `{token,userId}`（自动登录）；用户名路径 null（跳登录页） |
| POST | /api/user/login | `{"type":"username","username","password"}` 或 `{"type":"email","email","code"}` | `{token,userId}` |

- 验证码：6 位数字，Redis TTL 5 分钟，一次性；发送限流 60s/邮箱；错 5 次锁 10 分钟
- 邮箱登录未注册 = 自动注册（登录即注册），免密码
- JWT：subject=userId（唯一身份，贯穿对话/知识库/记忆），7 天过期，密钥 `.env` JWT_SECRET
- 账号合并（绑定/换绑邮箱触发，10-28 起开发）：保留发起方，被并入方须为纯邮箱账号

## 二、对话 `/api/chat`（已有）

| 方法 | 路径 | 入参 | 说明 |
|---|---|---|---|
| GET | /api/chat/stream?message= | 消息文本 | SSE 打字机流；首推 `event:streamId` 命名事件（拿它调 /stop）；内部走三层场景识别 → 按场景路由模型 |
| GET | /api/chat/stop?streamId= | 流 ID | 置停止标记 + complete + 从登记处移除 |

## 三、知识库 `/api/rag`（已有）

| 方法 | 路径 | 入参 | 说明 |
|---|---|---|---|
| POST | /api/rag/upload | multipart file + userId + kbId(缺省默认库) | 文件→Base64→rag_upload(is_base64=true)→parse/clean/chunk/embed/入库；返回 chunkCount |
| GET | /api/rag/list?userId= | | 知识库列表（kb_id \| name） |
| DELETE | /api/rag/delete?userId=&kbId= | | 删库（默认库=清空内容并重建，非默认库=真删） |
| GET | /api/rag/search?userId=&kbId=&topK=5 | | 语义检索（向量距离升序 + 内容去重，阈值过滤） |

- 私有库隔离：rag-mcp SQLite 按 user_id 分区 + KnowledgeBaseGuard 归属校验，越权直接拒 + stderr 审计日志
- mock 模式（mock-enabled=true）无 McpConnection bean → 503 降级不崩

## 四、用户配置 `/api/config`（已有）

| 方法 | 路径 | 入参 | 说明 |
|---|---|---|---|
| POST | /api/config/liepin-token | userId + token | 设置/更新猎聘 Token（每用户私有） |
| DELETE | /api/config/liepin-token | userId | 删除 |
| GET | /api/config/liepin-token | userId | 状态（configured + updatedAt，不返回明文） |

## 五、房源采集 `/api`（已有，内部工具）

| 方法 | 路径 | 入参 | 说明 |
|---|---|---|---|
| POST | /api/collect | userId + city + url | 爬链家→解析→写 rag_data/houses/{城市}.txt→重建索引 |
| POST | /api/reindex | userId + city(可空) | 手动编辑 txt 后只重建索引；city 空=全量重建 |

## 六、管理端 `/api/admin/*`（鉴权：X-Admin-Token）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/admin/health | 管理端连通性自检（Filter 校验通过才能到这） |

- 鉴权：请求头 `X-Admin-Token: <ADMIN_TOKEN>`（.env，gitignore），AdminAuthFilter 恒定时间比对，不一致 401
- 规划 Tab（10-30 开发）：RAG 管理 / 技能开关 / 调用统计 / 日志审计（traceId 查询）

## 规划中（W12 后续）

- /api/user/me：个人信息（脱敏 email、用户名、绑定状态）
- /api/user/bind-email：绑定/换绑邮箱（触发账号合并）
- /api/skill/*：技能清单/开关（管理端用）
- /api/memory/*：历史记录/清除（小程序记忆页 W13 用）
- /api/admin/*：管理端四 Tab

## 错误码约定

| code | 含义 |
|---|---|
| 0 | 成功 |
| 400 | 参数/格式错误（前端校验不通过的话术） |
| 401 | 未登录/凭证无效（登录失败统一话术不泄露细节） |
| 403 | 越权（R10：非本人数据） |
| 409 | 冲突（用户名/邮箱已被占用） |
| 429 | 限流（验证码 60s/锁定、治理内核单日限次） |
| 500 | 服务端错误 |
| 503 | 依赖能力不可用（mock 模式降级） |