package com.star.agent.media;

import com.alibaba.dashscope.aigc.multimodalconversation.AudioResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationOutput;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 语音合成服务单测：mock DashScope SDK，验证 base64 音频返回与降级（null 信号）
class DashScopeTtsServiceTest {

    private MultiModalConversation conv;
    private DashScopeTtsService service;

    @BeforeEach
    void setUp() {
        conv = mock(MultiModalConversation.class);
        service = new DashScopeTtsService("test-key", conv, java.net.http.HttpClient.newHttpClient());
    }

    @Test
    void synthesize_returnsAudioBytes() throws Exception {
        byte[] audio = new byte[]{1, 2, 3, 4};
        MultiModalConversationResult r = mock(MultiModalConversationResult.class);
        MultiModalConversationOutput out = mock(MultiModalConversationOutput.class);
        AudioResult audioResult = mock(AudioResult.class);
        when(r.getOutput()).thenReturn(out);
        when(out.getAudio()).thenReturn(audioResult);
        when(audioResult.getData()).thenReturn(Base64.getEncoder().encodeToString(audio));
        when(conv.call(any())).thenReturn(r);

        byte[] result = service.synthesize("你好", "Cherry");

        assertArrayEquals(audio, result);
    }

    @Test
    void synthesize_noAudio_returnsNull() throws Exception {
        MultiModalConversationResult r = mock(MultiModalConversationResult.class);
        when(r.getOutput()).thenReturn(null);
        when(conv.call(any())).thenReturn(r);

        assertNull(service.synthesize("你好", null), "无音频输出应返回 null 降级信号");
    }

    @Test
    void synthesize_exception_returnsNull() throws Exception {
        when(conv.call(any())).thenThrow(new RuntimeException("TTS 挂了"));

        assertNull(service.synthesize("你好", null));
    }

    @Test
    void synthesize_blankText_returnsNull() {
        assertNull(service.synthesize("  ", null));
    }
}