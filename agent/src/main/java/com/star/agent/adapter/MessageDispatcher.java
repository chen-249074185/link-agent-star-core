package com.star.agent.adapter;

import com.star.agent.adapter.wechat.WechatIdentityResolver;
import com.star.agent.message.AgentMessage;
import com.star.agent.message.MediaType;
import com.star.agent.message.MessageSource;
import com.star.agent.scene.Scene;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// 双线程消息管线：
// fetcher 线程 = 只管收（adapter.receive 长轮询 → 队列），不被 AI 回复阻塞
// processor 线程 = 只管干（200ms 队列轮询 → 场景识别 → chat → 回复），慢活不堵收消息
// 队列解耦 + seenMsgIds 去重（长轮询重复返回不重复回复）+ 断线自动重登（ReconnectHandler）
public class MessageDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(MessageDispatcher.class);
    private static final int PROCESSOR_POLL_MS = 200;   // processor 轮询间隔
    private static final int QUEUE_CAPACITY = 100;      // 队列容量：满了 fetcher 等位（背压），不无限积压
    private static final long FETCH_OFFER_TIMEOUT_SEC = 1;  // 队满时等位上限（跑着就等，停了就退）
    private static final long FETCH_IDLE_SLEEP_MS = 1000;     // 无消息空转间隔：getUpdates 是长轮询（服务端挂起等消息，
                                                           // 无消息 ~18s 超时返回 0 条），超时后的空转窗口决定消息最长等待
                                                           // （实测滞留 0s：消息下发即时，延迟只在空转窗口 → 3s 缩到 1s）
                                                           // 频率仍由长轮询主导（~1 次/20s），不会触发高频限流
    private static final long FETCH_ERROR_SLEEP_MS = 500;   // 异常后歇息（防异常风暴空转）
    private static final long RECONNECT_COOLDOWN_MS = 5000; // 重登冷却：5 秒内只重登一次（防断线风暴打爆登录）
    // 重登连续失败退避：顶号后没人扫码时重登必失败（二维码过期），连续 3 次后拉长到 5 分钟低频重试，
    // 避免控制台无限刷"需要扫码登录"（10-31 用户实测的刷屏死循环）；人工到网页端重新扫码即恢复
    private static final int RECONNECT_BACKOFF_MIN = 5;
    private static final long RECONNECT_BACKOFF_MS = RECONNECT_BACKOFF_MIN * 60_000L;

    private final MessageAdapter adapter;            // 通道（微信实现/未来企微实现）
    private final SceneDetector sceneDetector;
    private final ChatService chatService;
    private final ReconnectHandler reconnectHandler; // 断线重登回调（登录逻辑在实现方）
    private final BotCommandHandler commandHandler;  // 聊天命令拦截（#newbot/#logout），可空=通道不处理命令
    private final MultimediaHandler multimediaHandler; // 多媒体消息处理（识图/语音/文生图），可空=不处理
    private final DebounceProcessor debounce;         // 消息防抖聚合（连续消息攒批），可空=逐条处理
    private final ReplySender replySender;            // 回复发送编排（语音>图片>地图>文本），可空=直发文本
    private final WechatIdentityResolver identityResolver; // 微信身份→账号映射（绑定后微信与网页同一 userId），可空=不映射

    private final BlockingQueue<AgentMessage> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Set<String> seenMsgIds = ConcurrentHashMap.newKeySet();  // 去重登记本（消息 id → 已处理）
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread fetcherThread;
    private Thread processorThread;

    public MessageDispatcher(MessageAdapter adapter, SceneDetector sceneDetector,
                             ChatService chatService, ReconnectHandler reconnectHandler,
                             BotCommandHandler commandHandler, MultimediaHandler multimediaHandler,
                             DebounceProcessor debounce, ReplySender replySender) {
        this(adapter, sceneDetector, chatService, reconnectHandler,
                commandHandler, multimediaHandler, debounce, replySender, null);
    }

    // 9 参版：微信身份映射（Spring 链路传入；手动 main 链路可空=不映射，行为与绑定前一致）
    public MessageDispatcher(MessageAdapter adapter, SceneDetector sceneDetector,
                             ChatService chatService, ReconnectHandler reconnectHandler,
                             BotCommandHandler commandHandler, MultimediaHandler multimediaHandler,
                             DebounceProcessor debounce, ReplySender replySender,
                             WechatIdentityResolver identityResolver) {
        this.adapter = adapter;
        this.sceneDetector = sceneDetector;
        this.chatService = chatService;
        this.reconnectHandler = reconnectHandler;
        this.commandHandler = commandHandler;
        this.multimediaHandler = multimediaHandler;
        this.debounce = debounce;
        this.replySender = replySender;
        this.identityResolver = identityResolver;
    }

    public void start() {
        running.set(true);
        fetcherThread = new Thread(this::fetchLoop, "msg-fetcher");
        processorThread = new Thread(this::processLoop, "msg-processor");
        fetcherThread.setDaemon(true);
        processorThread.setDaemon(true);
        fetcherThread.start();
        processorThread.start();
        logger.info("MessageDispatcher 启动：fetcher+processor 双线程");
    }

    // 停止：置位后线程在下一个等待点自己退（线程不能强杀只能商量）
    public void stop() {
        running.set(false);
    }

    // 清空待处理消息（重登换 client 后调用）：旧 client 拉进队列的消息在新 client 上没有 context token，
    // 处理会导致 missing latest context token → 直接丢弃，让新 client 重新拉（消息可能重放但防重放登记保留）
    public void clearPending() {
        queue.clear();
        logger.info("重登换 client，已清空 {} 条待处理消息", queue.size());
    }

    // ===== fetcher：收消息 → 去重 → 入队（永远在收，不等人）=====
    private void fetchLoop() {
        long lastReconnectAt = 0;
        int reconnectFailures = 0;   // 连续重登失败次数：顶号后没人扫码时重登会一直失败，必须退避防刷屏
        while (running.get()) {
            try {
                AgentMessage am = adapter.receive();
                if (am == null) {
                    Thread.sleep(FETCH_IDLE_SLEEP_MS);   // 无消息也歇口气，不烧 CPU
                    continue;   // 通道暂无消息，下一轮再问
                }
                // 去重：同一 messageId 只处理一次（长轮询可能重复返回）；无 id 的消息不查重
                // 重复消息也要歇口气再轮询——否则服务端反复返回同批消息时这里会无间隔死循环调 getUpdates
                // （=高频轮询，10-17 教训：微信服务端对高频轮询限流，消息被压着不下发）
                if (am.messageId() != null && !seenMsgIds.add(am.messageId())) {
                    logger.info("跳过重复消息 {}", am.messageId());
                    Thread.sleep(FETCH_IDLE_SLEEP_MS);
                    continue;
                }
                // 入队：队满等位最多 1s（running 停止就退出不等）
                while (running.get() && !queue.offer(am, FETCH_OFFER_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    // 队满背压：等 processor 消化
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 通道失效（连接断开/token 过期）→ 自动重登，成功后换绑的 adapter 继续收
                // 重登冷却：5 秒内只重登一次，防断线风暴连续重登打爆登录接口
                long now = System.currentTimeMillis();
                if (now - lastReconnectAt >= RECONNECT_COOLDOWN_MS) {
                    lastReconnectAt = now;
                    logger.warn("消息拉取失败: {}，尝试自动重登...", e.getMessage());
                    try {
                        reconnectHandler.onDisconnected();
                        reconnectFailures = 0;   // 重登成功：清零失败计数
                    } catch (Exception re) {
                        reconnectFailures++;
                        logger.error("自动重登失败({}): {}", reconnectFailures, re.getMessage());
                        if (reconnectFailures >= 3) {
                            // 连续 3 次失败 = 顶号后没人扫码（二维码过期）：不再高频重试刷屏，
                            // 拉长间隔等人工（到网页端重新扫码即可恢复）
                            logger.warn("重登连续失败，{} 分钟内低频重试，请到网页端重新扫码", RECONNECT_BACKOFF_MIN);
                            try {
                                Thread.sleep(RECONNECT_BACKOFF_MS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            continue;
                        }
                    }
                } else {
                    logger.warn("消息拉取失败: {}，重登冷却中，{}ms 后再试",
                            e.getMessage(), RECONNECT_COOLDOWN_MS - (now - lastReconnectAt));
                }
                try {
                    Thread.sleep(FETCH_ERROR_SLEEP_MS);   // 异常后歇息，防异常风暴空转
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // ===== processor：队列 → 防抖聚合 → 逐条处理（场景识别 → 对话 → 回复）=====
    private void processLoop() {
        while (running.get()) {
            AgentMessage am;
            try {
                am = queue.poll(PROCESSOR_POLL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (am == null) {
                // 无新消息：防抖静默期到期的桶该产出了
                if (debounce != null) {
                    for (PendingMessage pending : debounce.checkDue(System.currentTimeMillis())) {
                        handlePending(pending);
                    }
                }
                continue;
            }
            if (debounce != null) {
                // 防抖聚合：攒够静默期（或触发词）才产出，防 AI 回复穿插/图+文字拆散
                PendingMessage pending = debounce.add(am.userId(), am, System.currentTimeMillis());
                if (pending != null) {
                    handlePending(pending);
                }
                continue;   // 还没齐：下一轮 poll 继续攒
            }
            handleMessage(am);
        }
    }

    // 聚合产物 → 按正常消息链路处理（命令/多媒体/chat 复用同一套）
    private void handlePending(PendingMessage pending) {
        // 聚合消息：文本用拼合结果；媒体类型/字节取第一条媒体消息（语音/图片/文件都要保留——
        // 只认 IMAGE 会把语音消息转成空文本走 chat，10-24 防抖接入时的 bug）
        MediaType mediaType = MediaType.TEXT;
        byte[] mediaBytes = null;
        for (AgentMessage m : pending.messages()) {
            if (m.mediaType() != MediaType.TEXT) {
                mediaType = m.mediaType();
                mediaBytes = m.mediaBytes();
                break;
            }
        }
        AgentMessage am = new AgentMessage(
                MessageSource.WECHAT,
                pending.userId(),
                pending.text(),
                mediaType,
                mediaBytes,
                null,   // 聚合消息无单一 messageId（去重已在入队前完成）
                pending.firstAt());
        handleMessage(am);
    }

    // 单条消息处理链：命令 → 多媒体 → 场景识别 → 对话 → 回复
    // 身份映射（10-31 三端互通）：对话/记忆/知识库走账号身份，回复目标保持微信地址
    private void handleMessage(AgentMessage am) {
        MDC.put("traceId", UUID.randomUUID().toString().substring(0, 8));  // 每条消息一个 traceId
        try {
            String wechatId = am.userId();   // 原始身份（微信地址，回复必须用它）
            String identity = identityResolver == null ? wechatId : identityResolver.resolve(wechatId);   // 数据归属身份
            if (!identity.equals(wechatId)) {
                logger.info("微信身份映射: {} → 账号 {}", wechatId, identity);
            }
            // 命令优先：是命令就直接处理回复，不花 AI 的 token
            if (commandHandler != null) {
                String cmdReply = commandHandler.handle(am.text(), wechatId);
                if (cmdReply != null) {
                    logger.info("命令处理[{}]: {} → {}", wechatId, am.text(), cmdReply);
                    sendText(wechatId, cmdReply);
                    return;
                }
            }
            // 多媒体优先：图片/语音/创作消息直接处理，不走文本 chat
            if (multimediaHandler != null) {
                String mmReply = multimediaHandler.handle(am, adapter);
                if (mmReply != null) {
                    if (!mmReply.isBlank()) {
                        sendText(wechatId, mmReply);
                    }
                    return;   // 已处理（含空串=发过图/语音，不回空消息）
                }
            }
            Scene scene = sceneDetector.detect(am.text());
            logger.info("收到消息 来自[{}]: {}，识别场景: {}", identity, am.text(), scene);
            String reply = chatService.chat(am.text(), identity, scene.name());
            logger.info("回复[{}]: {}", identity, reply);
            sendText(wechatId, reply);
        } catch (Exception e) {
            // 单条消息失败不影响后续：记日志继续处理下一条
            logger.error("处理消息失败 来自[{}]", am.userId(), e);
        } finally {
            MDC.remove("traceId");
        }
    }

    // 统一发送出口：有 ReplySender 走发送编排（未来回复带图/语音自动按优先级发），否则直发文本
    private void sendText(String userId, String text) throws Exception {
        if (replySender != null) {
            replySender.send(userId, Reply.text(text), adapter);
        } else {
            adapter.send(userId, text);
        }
    }
}