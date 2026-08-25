package com.star.agent.media;

// 语音转文字合同：输入 16k 单声道 WAV，输出文本（失败返回 null，调用方降级）
// 真实实现（DashScope paraformer）留后续接线；当前测试用 mock 注入
public interface VoiceTranscriber {

    String transcribe(byte[] wavBytes);
}