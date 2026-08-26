package com.star.agent.media;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.star.agent.infra.config.AppConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.Collections;

// 图片识别（qwen-vl-plus，DashScope 官方 SDK）：图片 byte[] → data URI → 返回内容描述
// 失败返回降级话术不抛异常（能力降级不崩，R9 同思路）
@Component
public class DashScopeVisionService {

    private static final String MODEL = "qwen-vl-plus";

    private final String apiKey;
    private final MultiModalConversation conv;

    @Autowired   // 多构造器时 Spring 靠它挑（坑15）
    public DashScopeVisionService(AppConfig config) {
        this(config.dashScopeApiKey(), new MultiModalConversation());
    }

    // 测试注入：mock SDK 类
    DashScopeVisionService(String apiKey, MultiModalConversation conv) {
        this.apiKey = apiKey;
        this.conv = conv;
    }

    // 识别图片内容；prompt 为空默认"请描述这张图片中的内容"
    public String analyzeImage(byte[] imageBytes, String mimeType, String prompt) {
        try {
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String dataUri = "data:" + (mimeType == null ? "image/png" : mimeType) + ";base64," + base64;
            MultiModalMessage userMessage = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Arrays.asList(
                            Collections.singletonMap("image", dataUri),
                            Collections.singletonMap("text",
                                    prompt == null || prompt.isBlank() ? "请描述这张图片中的内容" : prompt)))
                    .build();
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model(MODEL)
                    .messages(Arrays.asList(userMessage))
                    .build();
            MultiModalConversationResult result = conv.call(param);
            String text = extractText(result.getOutput().getChoices().get(0).getMessage().getContent());
            return text.isBlank() ? "识别图片时未返回内容，请稍后再试" : text;
        } catch (Exception e) {
            return "识别图片失败: " + e.getMessage();
        }
    }

    // 从多模态响应的 content（List<Map>，元素含 text 键）提取描述文本（纯函数，单测直接喂真实数据结构）
    static String extractText(List<?> content) {
        if (content == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : content) {
            if (item instanceof Map<?, ?> map && map.get("text") != null) {
                sb.append(map.get("text"));
            }
        }
        return sb.toString();
    }
}