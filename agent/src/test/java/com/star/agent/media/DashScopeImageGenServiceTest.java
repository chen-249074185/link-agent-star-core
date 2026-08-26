package com.star.agent.media;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisOutput;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 文生图服务单测：mock DashScope SDK（asyncCall + wait），验证 URL 返回与降级
class DashScopeImageGenServiceTest {

    private ImageSynthesis synthesis;
    private DashScopeImageGenService service;

    @BeforeEach
    void setUp() {
        synthesis = mock(ImageSynthesis.class);
        service = new DashScopeImageGenService("test-key", synthesis);
    }

    @Test
    void generate_returnsImageUrl() throws Exception {
        ImageSynthesisResult submit = mock(ImageSynthesisResult.class);
        ImageSynthesisOutput submitOut = mock(ImageSynthesisOutput.class);
        when(submit.getOutput()).thenReturn(submitOut);
        when(submitOut.getTaskId()).thenReturn("task-1");
        ImageSynthesisResult done = mock(ImageSynthesisResult.class);
        ImageSynthesisOutput doneOut = mock(ImageSynthesisOutput.class);
        when(done.getOutput()).thenReturn(doneOut);
        when(doneOut.getResults()).thenReturn(List.of(Map.of("url", "https://img.example.com/a.png")));
        when(synthesis.asyncCall(any(ImageSynthesisParam.class))).thenReturn(submit);
        when(synthesis.wait(anyString(), anyString())).thenReturn(done);

        String url = service.generate("一只赛博朋克风格的猫");

        assertEquals("https://img.example.com/a.png", url);
    }

    @Test
    void generate_noResults_returnsHelp() throws Exception {
        ImageSynthesisResult submit = mock(ImageSynthesisResult.class);
        ImageSynthesisOutput submitOut = mock(ImageSynthesisOutput.class);
        when(submit.getOutput()).thenReturn(submitOut);
        when(submitOut.getTaskId()).thenReturn("task-1");
        ImageSynthesisResult done = mock(ImageSynthesisResult.class);
        ImageSynthesisOutput doneOut = mock(ImageSynthesisOutput.class);
        when(done.getOutput()).thenReturn(doneOut);
        when(doneOut.getResults()).thenReturn(null);
        when(synthesis.asyncCall(any(ImageSynthesisParam.class))).thenReturn(submit);
        when(synthesis.wait(anyString(), anyString())).thenReturn(done);

        String url = service.generate("一只猫");

        assertTrue(url.contains("未返回结果"));
    }

    @Test
    void generate_sdkException_degrades() throws Exception {
        when(synthesis.asyncCall(any(ImageSynthesisParam.class))).thenThrow(new RuntimeException("任务提交失败"));

        String url = service.generate("一只猫");

        assertTrue(url.contains("图片生成失败"), "失败应降级话术不崩");
    }

    @Test
    void generate_blankPrompt_returnsHelp() {
        assertEquals("请描述你想生成的图片内容", service.generate("  "));
    }
}