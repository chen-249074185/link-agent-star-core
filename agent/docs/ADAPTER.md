# 通道适配层（MessageAdapter）使用文档

> W10（10-12 提前开工）引入：把"消息从哪来、回哪去"抽象成统一合同，业务层（BotWorker/未来的 MessageDispatcher）只认 `MessageAdapter`，不碰任何 SDK 类。

## 一、接口合同（adapter/MessageAdapter.java）

| 方法 | 方向 | 说明 |
|---|---|---|
| `AgentMessage receive()` | 收 | 轮询通道取一条内部统一格式消息；无消息返回 `null`（调用方自行决定轮询间隔） |
| `void send(userId, text)` | 发 | 文本回复 |
| `void sendImage(userId, imageBytes, fileName, caption)` | 发 | 图片（byte[] 原始字节 + 文件名 + 说明） |
| `void sendFile(userId, fileBytes, fileName, caption)` | 发 | 文件 |

## 二、微信实现（ILinkMessageAdapter）

- 构造无参，登录完成后 `attach(ILinkClient)` 绑定（登录/恢复都可能换 client，**换后必须重新 attach**，见坑7）
- 收方向：`client.getUpdates()` 一次可能带回**一批**消息，内部 pending 队列缓冲逐条吐，不丢消息
- 发方向：`send/sendImage/sendFile` 直通 SDK 同签名方法（SDK 还支持 sendVoice/sendTextWithTyping，需要时在此扩展）

## 三、接入新通道（企微/其他端预留）

1. 新建 `adapter/qywechat/QyWechatMessageAdapter implements MessageAdapter`
2. 实现四个方法：receive（轮询对方 SDK 取消息→`AgentMessage` 归一化）、send/sendImage/sendFile（翻译成对方 SDK 调用）
3. 登录态绑定走同一模式：登录完成后 `attach(对应 client)`，恢复/换号后重新 attach
4. 业务层零改动——BotWorker（以及 10-13 起的 MessageDispatcher）按接口编程，多通道只需装配不同的 adapter 实例

## 四、双线程管线（MessageDispatcher）

fetcher 线程只管收（adapter.receive → BlockingQueue），processor 线程只管干（200ms poll → 场景识别 → chat → send），队列解耦后收消息不被 AI 回复阻塞。

- **去重**：AgentMessage.messageId 记入 seenMsgIds（ConcurrentHashMap.newKeySet），长轮询重复返回同一条消息时跳过不重复回复；无 id 的消息不查重
- **断线自动重登**：receive 抛异常 → 调 ReconnectHandler.onDisconnected()（实现方重新登录并 attach 换绑）→ 继续收；**5 秒重登冷却**防断线风暴，异常/空闲时小睡防 CPU 空转
- **背压**：队列容量 100，队满时 fetcher 等位最多 1s，不让消息无限积压
- **单条失败隔离**：processor 处理一条消息抛异常只记日志，继续下一条

## 五、为什么这样设计

- **隔离 SDK**：微信 SDK 类（WeixinMessage 等）只出现在 adapter 包内，换通道/升级 SDK 业务层无感
- **统一消息模型**：内部一律 `AgentMessage`（source/userId/text/mediaType/mediaBytes/timestamp），三端（微信/Web/小程序）最终汇成同一模型
- **收发的缓冲语义**：receive 单条返回 + null 空转，天然适配"fetcher 拉批 + processor 逐条处理"的双线程管线（W10 第 2 天）