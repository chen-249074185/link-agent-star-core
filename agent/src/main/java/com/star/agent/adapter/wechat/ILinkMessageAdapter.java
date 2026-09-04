package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.star.agent.adapter.MessageAdapter;
import com.star.agent.message.AgentMessage;
import com.star.agent.message.MediaType;
import com.star.agent.message.MessageSource;

import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 微信通道适配器：把 wechat-ilink-sdk 的收发翻译成系统统一的 MessageAdapter 合同
// 收方向：getUpdates 一次可能带回一批消息，内部 pending 队列缓冲逐条吐（防丢批）
// 发方向：send/sendImage/sendFile 直通 SDK 同名单方法
public class ILinkMessageAdapter implements MessageAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ILinkMessageAdapter.class);

    static final int CHUNK_SIZE = 200;             // 分片大小：200 字/片（打字机节奏）
    static final int MAX_CHUNKS = 30;              // 最多 30 片（6000 字），超出截断防刷屏
    static final long CHUNK_INTERVAL_MS = 300;     // 片间停顿：模拟打字节奏，顺带防连发被限流

    private ILinkClient client;   // 登录完成后才绑定（attach）
    private final Queue<AgentMessage> pending = new LinkedList<>();

    // 登录/恢复完成后绑定可用 client（登录前后 client 可能被替换，必须重新 attach）
    public void attach(ILinkClient client) {
        this.client = client;
    }

    @Override
    public AgentMessage receive() throws Exception {
        if (client == null) {
            throw new IllegalStateException("adapter 未绑定 client，请先 attach");
        }
        if (pending.isEmpty()) {
            List<WeixinMessage> msgs = client.getUpdates();
            if (msgs != null) {
                for (WeixinMessage msg : msgs) {
                    pending.offer(convert(msg));
                }
            }
        }
        return pending.poll();
    }

    @Override
    public void send(String userId, String text) throws Exception {
        if (text == null) {
            return;
        }
        // markdown 超链接 [文字](url) → "文字：\nurl"：微信不渲染 markdown，URL 独立成行微信自动识别可点
        text = mdLinkToPlain(text);
        List<String> pieces = splitWithLinkProtection(text);
        if (pieces.size() <= 1) {
            client.sendText(userId, pieces.isEmpty() ? "" : pieces.get(0));
            return;
        }
        // 长回复分片模拟流式：微信 SDK 无"编辑已发消息"能力（无 updateText），
        // 用 startTyping（对方看到"正在输入"）+ 200 字/片逐条发送实现"打字机追加"效果
        boolean typing = false;
        try {
            client.startTyping(userId);
            typing = true;
        } catch (Exception e) {
            // 打字提示 RPC 失败（如 GetTypingTicket 被服务端拒绝）只是装饰效果，
            // 绝不能因此丢掉整条回复——降级为无打字提示直接分片发送
            logger.warn("startTyping 失败，降级为无打字提示分片发送: {}", e.getMessage());
        }
        try {
            for (String chunk : pieces) {
                client.sendText(userId, chunk);
                Thread.sleep(CHUNK_INTERVAL_MS);
            }
        } finally {
            if (typing) {
                client.stopTyping(userId);
            }
        }
    }

    @Override
    public void sendImage(String userId, byte[] imageBytes, String fileName, String caption) throws Exception {
        client.sendImage(userId, imageBytes, fileName, caption);
    }

    @Override
    public void sendVoice(String userId, byte[] voiceBytes, String fileName) throws Exception {
        client.sendVoice(userId, voiceBytes, fileName, null, null);
    }

    @Override
    public void sendFile(String userId, byte[] fileBytes, String fileName, String caption) throws Exception {
        client.sendFile(userId, fileBytes, fileName, caption);
    }

    // 微信消息 → 系统内部统一格式（收方向翻译：识别文本/图片/语音/文件 + 下载媒体字节）
    public AgentMessage convert(WeixinMessage msg) {
        String userId = msg.getFrom_user_id();

        StringBuilder text = new StringBuilder();
        MediaType mediaType = MediaType.TEXT;
        byte[] mediaBytes = null;
        if (msg.getItem_list() != null) {
            for (var item : msg.getItem_list()) {
                if (item.getText_item() != null) {
                    text.append(item.getText_item().getText());
                } else if (item.getImage_item() != null) {
                    mediaType = MediaType.IMAGE;
                    mediaBytes = downloadSafely(item);
                } else if (item.getVoice_item() != null) {
                    // 优先用微信服务端自带的语音转文字（voice_item.text）——实测我们自己 ASR 常识别不了格式
                    String autoText = item.getVoice_item().getText();
                    if (autoText != null && !autoText.isBlank()) {
                        text.append(autoText);
                    } else {
                        mediaType = MediaType.VOICE;
                        // 无转写文本才走我们自己的 ASR：语音必须用 SDK 专用下载（通用方法拉不到）
                        mediaBytes = downloadVoiceSafely(item);
                    }
                } else if (item.getFile_item() != null) {
                    mediaType = MediaType.FILE;
                    mediaBytes = downloadSafely(item);
                }
            }
        }
        return new AgentMessage(
                MessageSource.WECHAT,   // source：固定微信
                userId,                 // userId：第一步拿到的
                text.toString(),        // text：第二步拼好的文本
                mediaType,              // mediaType：按实际消息类型（文本/图片/语音/文件）
                mediaBytes,             // mediaBytes：媒体字节（下载失败为 null，降级纯文本）
                msg.getMessage_id() == null ? null : String.valueOf(msg.getMessage_id()),   // messageId：去重用
                Instant.now()           // timestamp：当前时间
        );
    }

    // 下载媒体字节：走 SDK downloadMediaFromMessageItem；失败不抛（返回 null，下游降级）
    private byte[] downloadSafely(com.github.wechat.ilink.sdk.core.model.MessageItem item) {
        try {
            return client == null ? null : client.downloadMediaFromMessageItem(item);
        } catch (Exception e) {
            return null;
        }
    }

    // 下载语音字节：SDK 专用 downloadVoiceFromMessageItem（参照：语音消息必须用专用下载）
    private byte[] downloadVoiceSafely(com.github.wechat.ilink.sdk.core.model.MessageItem item) {
        try {
            return client == null ? null : client.downloadVoiceFromMessageItem(item);
        } catch (Exception e) {
            return null;
        }
    }

    // markdown 超链接 [文字](url) → "文字：\nurl"（微信端专用：微信不渲染 markdown，
//     URL 独立成行后微信自动识别为可点击链接，且复制 URL 不被文字粘连）
// 顺带：剥 markdown 粗体 **；剥离 URL 行尾括号说明（模型重写可能把说明贴回链接行尾，复制会粘连）
static String mdLinkToPlain(String text) {
    if (text == null) {
        return null;
    }
    String t = text.replace("**", "");
    t = t.replaceAll("(amapuri://\\S+?)（[^）]*）", "$1");
    t = t.replaceAll("(https?://\\S+?)（[^）]*）", "$1");
    if (t.indexOf('[') < 0) {
        return t;
    }
    return t.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "$1：\n$2");
}

// 分片前按"独立链接行"保护：整行就是一个 URL（amapuri://、http(s):// 开头且不含空格）的
// 行作为原子片单独发送，绝不被 200 字分片从中间切断（否则复制/点击都会坏）；
// 其余普通文本继续走原分片逻辑
static List<String> splitWithLinkProtection(String text) {
    List<String> pieces = new LinkedList<>();
    StringBuilder buf = new StringBuilder();
    for (String line : text.split("\n", -1)) {
        String trimmed = line.trim();
        boolean linkLine = trimmed.startsWith("amapuri://")
                || trimmed.startsWith("http://") || trimmed.startsWith("https://");
        if (linkLine) {
            if (buf.length() > 0) {
                pieces.addAll(split(buf.toString()));
                buf.setLength(0);
            }
            pieces.add(line);
        } else {
            if (buf.length() > 0) {
                buf.append("\n");
            }
            buf.append(line);
        }
    }
    if (buf.length() > 0) {
        pieces.addAll(split(buf.toString()));
    }
    return pieces;
}

// 长文本分片（边界感知）：每片尽量接近 CHUNK_SIZE，但优先在段落/句子边界断开，
// 避免把一句话从中间切断（硬切 200 字会让下一条开头是半句话，阅读很丑）
// 边界优先级：换行 > 句子标点（。！？!）> 逗号/空格；无任何边界才硬切兜底
// 超 MAX_CHUNKS 截断并追加提示（截断保护）
static List<String> split(String text) {
    List<String> chunks = new LinkedList<>();
    int total = text.length();
    int start = 0;
    boolean truncated = false;
    while (start < total) {
        if (chunks.size() >= MAX_CHUNKS) {
            truncated = true;
            break;
        }
        int end = Math.min(start + CHUNK_SIZE, total);
        if (end < total) {
            int boundary = findBoundary(text, start, end);
            if (boundary > start) {
                end = boundary + 1;   // 边界字符（句号等）留在本片尾部
            }
        }
        chunks.add(text.substring(start, end));
        start = end;
    }
    if (truncated) {
        String last = chunks.get(chunks.size() - 1);
        chunks.set(chunks.size() - 1, last + "……（内容过长已截断）");
        logger.warn("长消息分片截断：原文 {} 字，只发前 {} 片", total, MAX_CHUNKS);
    }
    return chunks;
}

// 在 [from, to) 范围内找最后一个"语义边界"位置（返回边界字符索引）
// 同等级边界取靠后的（每片尽量装满）；返回 -1 = 无边界（调用方硬切）
static int findBoundary(String text, int from, int to) {
    int found = -1;
    int bestRank = -1;
    for (int i = from; i < to; i++) {
        int rank;
        char c = text.charAt(i);
        if (c == '\n' || c == '\r') {
            rank = 3;                       // 换行 = 最强边界（段落/行）
        } else if (c == '。' || c == '！' || c == '？' || c == '!') {
            rank = 2;                       // 句子结尾标点
        } else if (c == '；' || c == ';' || c == '.' || c == '，' || c == ',' || c == '、' || c == ' ') {
            rank = 1;                       // 逗号/空格类
        } else {
            rank = 0;
        }
        if (rank >= bestRank && rank > 0) {
            bestRank = rank;
            found = i;
        }
    }
    return found;
}
}