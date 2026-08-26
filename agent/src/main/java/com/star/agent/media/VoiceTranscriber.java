package com.star.agent.media;

// 语音转文字合同：输入音频字节（16k WAV 或原始 AMR 等，实现内部按魔数检测格式），输出文本（失败返回 null，调用方降级）
// 真实实现 = DashScopeAsrService（paraformer-realtime-v2，参照 our-project：format/sampleRate 必须声明）
public interface VoiceTranscriber {

    String transcribe(byte[] wavBytes);
}