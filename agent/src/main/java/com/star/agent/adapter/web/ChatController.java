package com.star.agent.adapter.web;

import com.star.agent.infra.web.AuthContext;
import com.star.agent.infra.web.Result;
import com.star.agent.memory.ChatHistoryService;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Tag(name = "对话", description = "AI 对话（打字机流式/非流式/历史会话/清空记忆），需 JWT")
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final SceneDetector sceneDetector;
    private final SseStreamRegistry streamRegistry;
    private final ChatHistoryService chatHistoryService;

    public ChatController(ChatService chatService, SceneDetector sceneDetector,
                          SseStreamRegistry streamRegistry, ChatHistoryService chatHistoryService) {
        this.chatService = chatService;
        this.sceneDetector = sceneDetector;
        this.streamRegistry = streamRegistry;
        this.chatHistoryService = chatHistoryService;
    }

    // 非流式对话（W13 小程序降级用）：一次性返回完整回复（内部走 chat() 工具循环 + 记忆双写）
    // 小程序模拟器/部分真机拿不到 SSE 流式响应（res.data 为空、onChunkReceived 不触发）→ 用这个端点
    // userId 从 JWT 取（AuthContext，与 /stream 一致）；sessionId 缺省=30 分钟内复用最近会话；newSession=强制新会话
    @Operation(summary = "非流式对话", description = "一次返回完整回复（小程序用，内部走工具循环+记忆双写）；sessionId 缺省=30 分钟内复用最近会话")
    @GetMapping("/ask")
    public Result<Map<String, Object>> ask(@RequestParam String message,
                                           @RequestParam(required = false) String sessionId,
                                           @RequestParam(required = false, defaultValue = "false") boolean newSession,
                                           HttpServletRequest request) {
        String userId = AuthContext.userId(request);
        if (userId == null) {
            return Result.fail(401, "未登录");
        }
        String scene = sceneDetector.detect(message).name();
        String session = chatService.resolveSessionId(userId, sessionId, newSession);
        // 落库与返回必须用同一个会话：chat() 传入解析结果，否则返回的 sessionId 与数据实际归属脱节
        String reply = chatService.chat(message, userId, scene, session);
        return Result.ok(Map.of("reply", reply, "sessionId", session));
    }

    // 打字机流：给每条流发"身份证"(streamId)并登记，支持 /stop 中途终止
    // userId 从 JWT 取（AuthContext）；sessionId 前端传（点历史继续聊=复用，留空=30 分钟内复用最近会话）；newSession=强制新会话
    @Operation(summary = "打字机流式对话（SSE）", description = "事件流：先推 streamId/sessionId 命名事件，正文逐 token 推 data 事件，工具调用前推 tool 事件")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String message,
                             @RequestParam(required = false) String sessionId,
                             @RequestParam(required = false, defaultValue = "false") boolean newSession,
                             HttpServletRequest request) {
        String streamId = UUID.randomUUID().toString().substring(0, 8);   // 流身份证
        SseEmitter emitter = new SseEmitter(120_000L);                     // 喇叭（120s 超时）
        AtomicBoolean stopped = new AtomicBoolean(false);                  // 停止标记：/stop 置 true，后台线程感知退出

        streamRegistry.register(streamId, emitter, stopped);               // 登记：/stop 才能找到这条流

        // 生命周期善后：流结束/超时 → 自动从登记处移除（防内存泄漏）+ 置停止标记（后台线程该退了）
        emitter.onCompletion(() -> streamRegistry.remove(streamId));
        emitter.onTimeout(() -> {
            stopped.set(true);
            streamRegistry.remove(streamId);
        });

        String scene = sceneDetector.detect(message).name();               // 三层识别定场景 → 决定走哪个模型
        String userId = AuthContext.userId(request);                       // JWT 里的身份（未登录=null）
        String session = chatService.resolveSessionId(userId, sessionId, newSession);  // 点历史=沿用旧会话，新对话=新 id
        String traceId = MDC.get("traceId");                               // 请求线程的 traceId 传给后台线程（runAsync 不继承 MDC）
        if (traceId == null || traceId.isBlank()) {                        // 切片测试无 TraceIdFilter 时自生成，行为一致
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        final String traceIdForThread = traceId;                           // lambda 只能捕获 effectively final

        CompletableFuture.runAsync(() -> {                                 // 后台线程跑"读流"，主线程立刻返回
            MDC.put("traceId", traceIdForThread);
            try {
                try {
                    // 先推两条命名事件给前端：streamId（才能调 /stop）+ sessionId（继续对话要带回去）
                    emitter.send(SseEmitter.event().name("streamId").data(streamId));
                    if (session != null) {
                        emitter.send(SseEmitter.event().name("sessionId").data(session));
                    }
                    // 流式 + 工具循环合流：正文推 data 事件（打字机），工具执行前推 tool 事件（前端展示调用状态）
                    chatService.streamWithTools(message, userId, session, scene,
                            token -> {
                                if (stopped.get()) {                                    // 已点停止：不再推，抛出信号让读流线程退出
                                    throw new RuntimeException("stream stopped");
                                }
                                try {
                                    emitter.send(SseEmitter.event().data(token));
                                } catch (IOException e) {
                                    throw new RuntimeException(e);                      // 浏览器关了/断线 → 也退出
                                }
                            },
                            toolName -> {
                                try {
                                    emitter.send(SseEmitter.event().name("tool").data(toolName));
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                    emitter.complete();                                         // 正常推完
                } catch (Exception e) {
                    // 只有"真报错"才 completeWithError；主动停止/断线不算错，静默结束即可
                    if (!"stream stopped".equals(e.getMessage())) {
                        emitter.completeWithError(e);
                    }
                }
            } finally {
                MDC.remove("traceId");
            }
        });

        return emitter;
    }

    // 流式中途终止：前端点"停止"按钮调这里，按 streamId 找到那条流并终止
    @Operation(summary = "停止流式对话", description = "按 /stream 首推的 streamId 中途终止该条流")
    @GetMapping("/stop")
    public String stop(@RequestParam String streamId) {
        boolean stopped = streamRegistry.stop(streamId);
        logger.info("停止流 {}: {}", streamId, stopped ? "已终止" : "未找到(可能已结束)");
        return stopped ? "已停止" : "流不存在或已结束";
    }

    // 历史会话列表（最新在前）：{sessionId, title, time, count}；左侧"最近"栏数据源
    @Operation(summary = "历史会话列表", description = "最新在前：{sessionId, title, time, count}")
    @GetMapping("/history")
    public Result<List<Map<String, Object>>> history(@RequestParam String userId) {
        return Result.ok(chatHistoryService.list(userId));
    }

    // 会话详情（点开某条历史）：{role, content, time} 列表
    @Operation(summary = "会话详情", description = "某条会话的消息列表 {role, content, time}")
    @GetMapping("/history/{sessionId}")
    public Result<List<Map<String, Object>>> historyDetail(@PathVariable String sessionId,
                                                           @RequestParam String userId) {
        return Result.ok(chatHistoryService.detail(userId, sessionId));
    }

    // 删除会话（最近项 hover 删除）
    @Operation(summary = "删除会话")
    @DeleteMapping("/history/{sessionId}")
    public Result<String> deleteHistory(@PathVariable String sessionId,
                                        @RequestParam String userId) {
        if (!chatHistoryService.delete(userId, sessionId)) {
            return Result.fail(400, "会话不存在或已删除");
        }
        return Result.ok("已删除该会话");
    }

    // 清空全部记忆（小程序记忆页"清空全部"）：对话 + 每日摘要 + Redis 瞬时记忆三处全清
    @Operation(summary = "清空全部记忆", description = "对话 + 每日摘要 + Redis 瞬时记忆三处全清")
    @DeleteMapping("/clear-all")
    public Result<String> clearAll(@RequestParam String userId) {
        int n = chatHistoryService.clearAll(userId);
        return Result.ok(n > 0 ? "已清空全部记忆（" + n + " 条对话）" : "记忆本就是空的");
    }

    // 历史搜索：按内容关键词，命中行归并成会话（最新在前）
    @Operation(summary = "历史搜索", description = "按内容关键词，命中行归并成会话（最新在前）")
    @GetMapping("/search")
    public Result<List<Map<String, Object>>> searchHistory(@RequestParam String userId,
                                                           @RequestParam String q) {
        return Result.ok(chatHistoryService.search(userId, q));
    }
}