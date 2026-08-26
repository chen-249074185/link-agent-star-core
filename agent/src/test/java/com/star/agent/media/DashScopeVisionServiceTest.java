package com.star.agent.media;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 图片识别服务单测：解析纯函数（真实数据结构）+ 异常降级（mock SDK 边界）
class DashScopeVisionServiceTest {

    private MultiModalConversation conv;
    private DashScopeVisionService service;

    @BeforeEach
    void setUp() {
        conv = mock(MultiModalConversation.class);
        service = new DashScopeVisionService("test-key", conv);
    }

    // 解析纯函数：content（List<Map> 含 text 键）→ 描述文本
    @Test
    void extractText_joinsTextEntries() {
        String text = DashScopeVisionService.extractText(
                List.of(Map.of("text", "一只猫"), Map.of("text", "在沙发上")));

        assertEquals("一只猫在沙发上", text);
    }

    @Test
    void extractText_noText_returnsEmpty() {
        assertEquals("", DashScopeVisionService.extractText(List.of(Map.of("image", "data:..."))));
        assertEquals("", DashScopeVisionService.extractText(null));
    }

    // SDK 调用异常 → 降级话术不崩
    @Test
    void analyzeImage_sdkException_degrades() throws Exception {
        when(conv.call(any())).thenThrow(new RuntimeException("API 挂了"));

        String result = service.analyzeImage(new byte[]{1, 2, 3}, "image/png", null);

        assertTrue(result.contains("识别图片失败"));
    }
}