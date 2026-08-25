package com.star.agent.media;

// 语音格式（魔数检测结果）：微信语音常见 5 格式 + 未识别
public enum AudioFormat {
    AMR,    // "#!AMR" — 微信语音经典格式
    SILK,   // "#!SILK" — 微信/QQ 语音格式
    WAV,    // "RIFF" — 未压缩波形
    MP3,    // "ID3" 或 MPEG 帧同步 0xFFFx — 常见录音
    OGG,    // "OggS" — 开放容器
    UNKNOWN // 未识别：无法转码，交给降级
}