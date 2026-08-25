package com.star.agent.adapter;

// 一条 AI 回复的四个候选载体：发送时按优先级 语音 > 图片 > 地图/二维码 > 文本 只发最高档
public record Reply(
        String text,      // 文本（最低优先级，兜底）
        String imageUrl,  // 图片 URL（中档：下载后发图片，失败降级文本）
        String mapUrl,    // 内嵌地图/二维码 URL（同图片处理，仅当无图片时用）
        byte[] voiceBytes // 语音字节（最高优先级）
) {
    public static Reply text(String text) {
        return new Reply(text, null, null, null);
    }
}