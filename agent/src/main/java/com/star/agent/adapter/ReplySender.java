package com.star.agent.adapter;

// 发送编排：一条 Reply 按优先级 语音 > 图片 > 地图/二维码 > 文本 只发最高档（不重复轰炸）
// 图片/地图是 URL → 先下载再发图；下载失败降级纯文本（不回显空消息）
public class ReplySender {

    private final ImageFetcher imageFetcher;

    public ReplySender(ImageFetcher imageFetcher) {
        this.imageFetcher = imageFetcher;
    }

    public void send(String userId, Reply reply, MessageAdapter adapter) throws Exception {
        // ① 语音最高优先
        if (reply.voiceBytes() != null) {
            adapter.sendVoice(userId, reply.voiceBytes(), "voice.wav");
            return;
        }
        // ② 图片（URL 下载 → 发图；下载失败降级文本）
        if (reply.imageUrl() != null) {
            sendUrlImage(userId, reply.imageUrl(), adapter, reply.text());
            return;
        }
        // ③ 内嵌地图/二维码（URL 图片，无图片时才用它）
        if (reply.mapUrl() != null) {
            sendUrlImage(userId, reply.mapUrl(), adapter, reply.text());
            return;
        }
        // ④ 文本兜底（空文本不发）
        if (reply.text() != null && !reply.text().isBlank()) {
            adapter.send(userId, reply.text());
        }
    }

    // URL 图片发送：下载成功发图；失败降级纯文本（文本为空则干脆不发，避免空消息）
    private void sendUrlImage(String userId, String url, MessageAdapter adapter, String fallbackText) throws Exception {
        try {
            byte[] img = imageFetcher.fetch(url);
            adapter.sendImage(userId, img, "image.png", "");
        } catch (Exception e) {
            if (fallbackText != null && !fallbackText.isBlank()) {
                adapter.send(userId, fallbackText);
            }
        }
    }
}