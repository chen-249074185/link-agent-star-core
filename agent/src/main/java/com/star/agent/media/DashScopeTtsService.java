package com.star.agent.media;

import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.aigc.multimodalconversation.AudioResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.star.agent.infra.config.AppConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;

// 语音合成 TTS（qwen3-tts-flash，DashScope 官方 SDK）：文字 → 音频（优先 base64，否则 URL 下载）
// 失败返回 null（降级信号，调用方换话术，不崩）
@Component
public class DashScopeTtsService {

    private static final String MODEL = "qwen3-tts-flash";
    private static final String DEFAULT_VOICE = "Cherry";

    private final String apiKey;
    private final MultiModalConversation conv;
    private final HttpClient http;

    @Autowired   // 多构造器时 Spring 靠它挑
    public DashScopeTtsService(AppConfig config) {
        this(config.dashScopeApiKey(), new MultiModalConversation(), HttpClient.newHttpClient());
    }

    // 测试注入：mock SDK 类
    DashScopeTtsService(String apiKey, MultiModalConversation conv, HttpClient http) {
        this.apiKey = apiKey;
        this.conv = conv;
        this.http = http;
    }

    // 文字转语音，返回 WAV 字节；失败返回 null
    public byte[] synthesize(String text, String voice) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            AudioParameters.Voice voiceEnum;
            try {
                voiceEnum = AudioParameters.Voice.valueOf(
                        (voice == null || voice.isBlank() ? DEFAULT_VOICE : voice).toUpperCase());
            } catch (IllegalArgumentException e) {
                voiceEnum = AudioParameters.Voice.CHERRY;   // 未知音色回落默认
            }
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model(MODEL)
                    .voice(voiceEnum)
                    .text(text)
                    .build();
            MultiModalConversationResult result = conv.call(param);
            if (result.getOutput() == null || result.getOutput().getAudio() == null) {
                return null;
            }
            String data = result.getOutput().getAudio().getData();
            if (data != null && !data.isBlank()) {
                return Base64.getDecoder().decode(data);
            }
            String url = result.getOutput().getAudio().getUrl();
            if (url != null && !url.isBlank()) {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
                return http.send(req, HttpResponse.BodyHandlers.ofByteArray()).body();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}