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

// 微信通道适配器：把 wechat-ilink-sdk 的收发翻译成系统统一的 MessageAdapter 合同
// 收方向：getUpdates 一次可能带回一批消息，内部 pending 队列缓冲逐条吐（防丢批）
// 发方向：send/sendImage/sendFile 直通 SDK 同名单方法
public class ILinkMessageAdapter implements MessageAdapter {

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
        client.sendText(userId, text);
    }

    @Override
    public void sendImage(String userId, byte[] imageBytes, String fileName, String caption) throws Exception {
        client.sendImage(userId, imageBytes, fileName, caption);
    }

    @Override
    public void sendFile(String userId, byte[] fileBytes, String fileName, String caption) throws Exception {
        client.sendFile(userId, fileBytes, fileName, caption);
    }

    // 微信消息 → 系统内部统一格式（收方向翻译；10-15 升级：识别图片/语音/文件 + 下载媒体字节）
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
                    mediaType = MediaType.VOICE;
                    mediaBytes = downloadSafely(item);
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
}