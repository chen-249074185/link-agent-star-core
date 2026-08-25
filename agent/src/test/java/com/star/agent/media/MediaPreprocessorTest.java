package com.star.agent.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// MediaPreprocessor 编排单测：图片 base64 / 语音全链路 / 三级降级（格式未知/转码失败/转写失败）
class MediaPreprocessorTest {

    private final AudioFormatDetector detector = new AudioFormatDetector();
    private final AudioTranscoder transcoder = mock(AudioTranscoder.class);
    private final VoiceTranscriber transcriber = mock(VoiceTranscriber.class);
    private final MediaPreprocessor pre = new MediaPreprocessor(detector, transcoder, transcriber);

    private byte[] amrVoice() {
        return ("#!AMR" + "payload").getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void image_toBase64() {
        byte[] img = new byte[]{1, 2, 3, 4};
        assertEquals(Base64.getEncoder().encodeToString(img), pre.imageToBase64(img));
    }

    @Test
    void voice_fullPipeline() {
        when(transcoder.to16kWav(any())).thenReturn(new byte[]{9, 9});
        when(transcriber.transcribe(any())).thenReturn("明天上午十点开会");

        assertEquals("明天上午十点开会", pre.voiceToText(amrVoice()));
    }

    @Test
    void voice_unknownFormat_degrades() {
        byte[] unknown = "not-a-voice".getBytes(StandardCharsets.UTF_8);
        String result = pre.voiceToText(unknown);
        assertTrue(result.contains("无法识别音频格式"), "未知格式应降级话术");
    }

    @Test
    void voice_transcodeFailure_degrades() {
        when(transcoder.to16kWav(any())).thenReturn(null);   // ffmpeg 缺失（R7）

        String result = pre.voiceToText(amrVoice());

        assertTrue(result.contains("转码失败"), "ffmpeg 缺失应降级话术");
    }

    @Test
    void voice_transcribeFailure_degrades() {
        when(transcoder.to16kWav(any())).thenReturn(new byte[]{9, 9});
        when(transcriber.transcribe(any())).thenReturn(null);   // 转写不出文字

        String result = pre.voiceToText(amrVoice());

        assertTrue(result.contains("未能识别出文字"), "转写失败应降级话术");
    }
}