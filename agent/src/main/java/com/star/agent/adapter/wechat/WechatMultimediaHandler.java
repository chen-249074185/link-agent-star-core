package com.star.agent.adapter.wechat;

import com.star.agent.adapter.ImageFetcher;
import com.star.agent.adapter.MessageAdapter;
import com.star.agent.adapter.MultimediaHandler;
import com.star.agent.media.DashScopeImageGenService;
import com.star.agent.media.DashScopeTtsService;
import com.star.agent.media.DashScopeVisionService;
import com.star.agent.media.MediaPreprocessor;
import com.star.agent.message.AgentMessage;
import com.star.agent.message.MediaType;
import com.star.agent.scene.Scene;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Base64;

// 微信多媒体消息处理：
//   图片消息 → 识图（qwen-vl-plus）→ 回复描述
//   语音消息 → ffmpeg 转码 → ASR 转写 → 当用户文本消息走正常对话
//   创作场景（画一只猫）→ 文生图（wanx-v1）→ 下载图片 → 发图
// 所有能力失败降级话术，不抛异常不崩
@Component
public class WechatMultimediaHandler implements MultimediaHandler {

    private static final Logger logger = LoggerFactory.getLogger(WechatMultimediaHandler.class);

    private final DashScopeVisionService visionService;
    private final DashScopeImageGenService imageGenService;
    private final DashScopeTtsService ttsService;
    private final MediaPreprocessor mediaPreprocessor;
    private final ImageFetcher imageFetcher;
    private final ChatService chatService;
    private final SceneDetector sceneDetector;

    public WechatMultimediaHandler(DashScopeVisionService visionService,
                                   DashScopeImageGenService imageGenService,
                                   DashScopeTtsService ttsService,
                                   MediaPreprocessor mediaPreprocessor,
                                   ImageFetcher imageFetcher,
                                   ChatService chatService,
                                   SceneDetector sceneDetector) {
        this.visionService = visionService;
        this.imageGenService = imageGenService;
        this.ttsService = ttsService;
        this.mediaPreprocessor = mediaPreprocessor;
        this.imageFetcher = imageFetcher;
        this.chatService = chatService;
        this.sceneDetector = sceneDetector;
    }

    @Override
    public String handle(AgentMessage msg, MessageAdapter adapter) throws Exception {
        if (msg.mediaType() == MediaType.IMAGE) {
            return handleImage(msg, adapter);
        }
        if (msg.mediaType() == MediaType.VOICE) {
            return handleVoice(msg);
        }
        if (msg.mediaType() == MediaType.TEXT && msg.text() != null && msg.text().trim().startsWith("#tts ")) {
            return handleTts(msg, adapter);
        }
        if (msg.mediaType() == MediaType.TEXT && sceneDetector.detect(msg.text()) == Scene.CREATION) {
            return handleCreation(msg, adapter);
        }
        return null;   // 纯文本非创作：走正常对话
    }

    // 语音合成：#tts 内容 → 先回"已收到"即时反馈 → 合成语音发出去（TTS 能力入口）
    private String handleTts(AgentMessage msg, MessageAdapter adapter) throws Exception {
        String content = msg.text().trim().substring("#tts ".length());
        if (content.isBlank()) {
            return "用法：#tts 要合成的文字，例如 #tts 你好，欢迎使用星链智核";
        }
        adapter.send(msg.userId(), "已收到，正在合成语音，请稍候…");
        byte[] voice = ttsService.synthesize(content, null);
        if (voice == null) {
            return "语音合成失败，请稍后再试";
        }
        adapter.sendVoice(msg.userId(), voice, "tts.wav");
        return "";   // 已发语音，空串=处理完毕
    }

    // 图片 → 识图描述（先回"已收到"即时反馈）
    private String handleImage(AgentMessage msg, MessageAdapter adapter) throws Exception {
        if (msg.mediaBytes() == null) {
            return "收到图片但下载失败，请重发或改发文字";
        }
        adapter.send(msg.userId(), "已收到图片，正在识别，请稍候…");
        String description = visionService.analyzeImage(msg.mediaBytes(), "image/png", null);
        logger.info("识图[{}]: {}", msg.userId(), description);
        return "识别结果：\n" + description;
    }

    // 语音 → 转写 → 走正常对话（转写失败由 MediaPreprocessor 返回降级话术）
    private String handleVoice(AgentMessage msg) {
        if (msg.mediaBytes() == null) {
            return "收到语音但下载失败，请重发或改发文字";
        }
        String text = mediaPreprocessor.voiceToText(msg.mediaBytes());
        if (!text.contains("收到语音")) {   // 转写成功 → 正常对话
            Scene scene = sceneDetector.detect(text);
            logger.info("语音转写[{}]: {}（场景 {}）", msg.userId(), text, scene);
            return chatService.chat(text, msg.userId(), scene.name());
        }
        return text;   // 降级话术原样回
    }

    // 创作场景 → 文生图（先回"已收到"即时反馈——wanx 异步生成要几十秒，不能让用户干等）
    private String handleCreation(AgentMessage msg, MessageAdapter adapter) throws Exception {
        adapter.send(msg.userId(), "已收到，正在为你生成图片，请稍候…");
        String url = imageGenService.generate(msg.text());
        if (!url.startsWith("http")) {
            logger.info("创作[{}] 生成失败: {}", msg.userId(), url);
            return url;   // 降级话术
        }
        try {
            byte[] image = imageFetcher.fetch(url);
            adapter.sendImage(msg.userId(), image, "creation.png", "");
            logger.info("创作[{}] 已发图: {}（提示词: {}）", msg.userId(), url, msg.text());
            return "";   // 已发图：空串=已处理完无需回文本（dispatcher 不回空消息）
        } catch (Exception e) {
            logger.warn("创作图片下载失败: {}", e.getMessage());
            return "图片已生成（" + url + "），但下载失败，请稍后再试";
        }
    }
}