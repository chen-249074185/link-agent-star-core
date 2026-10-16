package com.star.agent.media;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.star.agent.infra.config.AppConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

// 文生图（wanx-v1，DashScope 官方 SDK）：文字描述 → 异步任务 → 轮询完成 → 图片 URL
// 失败返回降级话术不抛异常（能力降级不崩）
@Component
public class DashScopeImageGenService {

    private static final String MODEL = "wanx-v1";
    private static final String SIZE = "1024*1024";

    private final String apiKey;
    private final ImageSynthesis imageSynthesis;

    @Autowired   // 多构造器时 Spring 靠它挑
    public DashScopeImageGenService(AppConfig config) {
        this(config.dashScopeApiKey(), new ImageSynthesis());
    }

    // 测试注入：mock SDK 类
    DashScopeImageGenService(String apiKey, ImageSynthesis imageSynthesis) {
        this.apiKey = apiKey;
        this.imageSynthesis = imageSynthesis;
    }

    // 生成图片，返回图片 URL；失败返回降级话术
    public String generate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "请描述你想生成的图片内容";
        }
        try {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("watermark", "false");
            parameters.put("prompt_extend", "true");
            parameters.put("negative_prompt",
                    "低分辨率，低画质，肢体畸形，手指畸形，画面过饱和，人脸无细节，构图混乱");

            ImageSynthesisParam param = ImageSynthesisParam.builder()
                    .model(MODEL)
                    .apiKey(apiKey)
                    .prompt(prompt)
                    .size(SIZE)
                    .n(1)
                    .parameters(parameters)
                    .build();
            ImageSynthesisResult result = imageSynthesis.asyncCall(param);
            String taskId = result.getOutput().getTaskId();
            ImageSynthesisResult finalResult = imageSynthesis.wait(taskId, apiKey);
            if (finalResult.getOutput().getResults() == null
                    || finalResult.getOutput().getResults().isEmpty()) {
                return "图片生成未返回结果，请稍后再试";
            }
            return finalResult.getOutput().getResults().get(0).get("url");
        } catch (Exception e) {
            return "图片生成失败: " + e.getMessage();
        }
    }
}