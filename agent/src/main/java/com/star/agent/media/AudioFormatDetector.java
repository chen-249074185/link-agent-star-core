package com.star.agent.media;

import java.nio.charset.StandardCharsets;

// 语音格式魔数检测：看文件头几个字节就能认出格式，不用依赖扩展名/外部工具
// 微信语音常见 5 格式 + 未识别（UNKNOWN 交给降级）
public class AudioFormatDetector {

    // 魔数：ASCII 头
    private static final byte[] AMR_HEAD = "#!AMR".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SILK_HEAD = "#!SILK".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WAV_HEAD = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MP3_ID3 = "ID3".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OGG_HEAD = "OggS".getBytes(StandardCharsets.US_ASCII);

    // 检测格式：按魔数逐一比对；字节太短（<4）无法判断
    public AudioFormat detect(byte[] data) {
        if (data == null || data.length < 4) {
            return AudioFormat.UNKNOWN;
        }
        if (startsWith(data, AMR_HEAD)) return AudioFormat.AMR;
        if (startsWith(data, SILK_HEAD)) return AudioFormat.SILK;
        if (startsWith(data, WAV_HEAD)) return AudioFormat.WAV;
        if (startsWith(data, MP3_ID3) || isMpegFrameSync(data)) return AudioFormat.MP3;
        if (startsWith(data, OGG_HEAD)) return AudioFormat.OGG;
        return AudioFormat.UNKNOWN;
    }

    private boolean startsWith(byte[] data, byte[] head) {
        if (data.length < head.length) {
            return false;
        }
        for (int i = 0; i < head.length; i++) {
            if (data[i] != head[i]) return false;
        }
        return true;
    }

    // MPEG 帧同步：无 ID3 标签的裸 MP3 以 0xFF 0xEx/0xFF 0xFx 开头（同步字 11 个 1）
    private boolean isMpegFrameSync(byte[] data) {
        return data[0] == (byte) 0xFF
                && ((data[1] & 0xE0) == 0xE0);
    }
}