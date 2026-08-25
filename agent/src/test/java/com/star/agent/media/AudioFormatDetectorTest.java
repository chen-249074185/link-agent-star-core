package com.star.agent.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

// 语音格式魔数检测单测：5 格式 + 未识别（字节头判定，零外部依赖）
class AudioFormatDetectorTest {

    private final AudioFormatDetector detector = new AudioFormatDetector();

    private byte[] head(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void detectsAmr() {
        assertEquals(AudioFormat.AMR, detector.detect(head("#!AMR" + "some data")));
    }

    @Test
    void detectsSilk() {
        assertEquals(AudioFormat.SILK, detector.detect(head("#!SILK_V3" + "data")));
    }

    @Test
    void detectsWav() {
        assertEquals(AudioFormat.WAV, detector.detect(head("RIFF....WAVEfmt")));
    }

    @Test
    void detectsMp3_withId3Tag() {
        assertEquals(AudioFormat.MP3, detector.detect(head("ID3" + "tag data")));
    }

    @Test
    void detectsMp3_withMpegFrameSync() {
        assertEquals(AudioFormat.MP3, detector.detect(new byte[]{(byte) 0xFF, (byte) 0xFB, 0x10, 0x00}));
    }

    @Test
    void detectsOgg() {
        assertEquals(AudioFormat.OGG, detector.detect(head("OggS" + "stream")));
    }

    @Test
    void unknownFormat() {
        assertEquals(AudioFormat.UNKNOWN, detector.detect(head("XXXXunknown")));
    }

    @Test
    void tooShort_isUnknown() {
        assertEquals(AudioFormat.UNKNOWN, detector.detect(new byte[]{0x23, 0x21}));
        assertEquals(AudioFormat.UNKNOWN, detector.detect(null));
    }
}