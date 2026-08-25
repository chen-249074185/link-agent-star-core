package com.star.agent.media;

import java.util.Base64;

// 多媒体预处理编排：
// 图片 → base64（给多模态 LLM 识图）；语音 → 魔数检测 → ffmpeg 转 16k WAV → 转文字
// 降级链（R7）：转码失败（ffmpeg 缺失/格式不支持）→ 返回降级话术，不抛异常不崩
public class MediaPreprocessor {

    private final AudioFormatDetector detector;
    private final AudioTranscoder transcoder;
    private final VoiceTranscriber transcriber;

    public MediaPreprocessor(AudioFormatDetector detector, AudioTranscoder transcoder, VoiceTranscriber transcriber) {
        this.detector = detector;
        this.transcoder = transcoder;
        this.transcriber = transcriber;
    }

    // 图片 → Base64 字符串（文本可携带，多模态接口用它）
    public String imageToBase64(byte[] imageBytes) {
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    // 语音 → 文本：检测格式 → 转 16k WAV → 转写
    // 转码失败/格式未知 → 降级话术（不崩）；转写失败 → 同样降级话术
    public String voiceToText(byte[] voiceBytes) {
        AudioFormat format = detector.detect(voiceBytes);
        if (format == AudioFormat.UNKNOWN) {
            return "收到语音，但无法识别音频格式，请发文字或重发";
        }
        byte[] wav = transcoder.to16kWav(voiceBytes);
        if (wav == null) {
            return "收到语音，但转码失败（环境缺 ffmpeg），请发文字";
        }
        String text = transcriber.transcribe(wav);
        if (text == null || text.isBlank()) {
            return "收到语音，但未能识别出文字，请发文字";
        }
        return text;
    }
}