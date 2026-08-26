package com.star.agent.media;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 语音识别服务单测：解析纯函数（真实 JSON）+ 异常降级（mock SDK 边界）
class DashScopeAsrServiceTest {

    private Recognition recognition;
    private DashScopeAsrService service;

    @BeforeEach
    void setUp() {
        recognition = mock(Recognition.class);
        service = new DashScopeAsrService("test-key", recognition);
    }

    // 解析纯函数：sentences[].text 逗号拼接
    @Test
    void parseSentences_joinsText() {
        String json = "{\"sentences\":[{\"text\":\"今天天气\"},{\"text\":\"怎么样\"}]}";

        assertEquals("今天天气，怎么样", DashScopeAsrService.parseSentences(json));
    }

    @Test
    void parseSentences_blankOrBroken_returnsEmpty() {
        assertEquals("", DashScopeAsrService.parseSentences("{\"sentences\":[{\"text\":\"  \"}]}"));
        assertEquals("", DashScopeAsrService.parseSentences("not json"));
    }

    // SDK 调用异常 → null 降级信号
    @Test
    void transcribe_exception_returnsNull() throws Exception {
        when(recognition.call(any(RecognitionParam.class), any(File.class)))
                .thenThrow(new RuntimeException("ASR 挂了"));

        assertNull(service.transcribe(new byte[]{1, 2, 3}));
    }
}