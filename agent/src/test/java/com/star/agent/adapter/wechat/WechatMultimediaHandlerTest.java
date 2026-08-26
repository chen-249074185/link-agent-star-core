package com.star.agent.adapter.wechat;

import com.star.agent.adapter.ImageFetcher;
import com.star.agent.adapter.MessageAdapter;
import com.star.agent.media.DashScopeImageGenService;
import com.star.agent.media.DashScopeTtsService;
import com.star.agent.media.DashScopeVisionService;
import com.star.agent.media.MediaPreprocessor;
import com.star.agent.message.AgentMessage;
import com.star.agent.message.MediaType;
import com.star.agent.message.MessageSource;
import com.star.agent.scene.Scene;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 微信多媒体处理器单测：图片识图/语音转写对话/创作文生图发图/纯文本放行
class WechatMultimediaHandlerTest {

    private DashScopeVisionService vision;
    private DashScopeImageGenService imageGen;
    private DashScopeTtsService tts;
    private MediaPreprocessor preprocessor;
    private ImageFetcher imageFetcher;
    private ChatService chatService;
    private SceneDetector sceneDetector;
    private MessageAdapter adapter;
    private WechatMultimediaHandler handler;

    @BeforeEach
    void setUp() {
        vision = mock(DashScopeVisionService.class);
        imageGen = mock(DashScopeImageGenService.class);
        tts = mock(DashScopeTtsService.class);
        preprocessor = mock(MediaPreprocessor.class);
        imageFetcher = mock(ImageFetcher.class);
        chatService = mock(ChatService.class);
        sceneDetector = mock(SceneDetector.class);
        adapter = mock(MessageAdapter.class);
        handler = new WechatMultimediaHandler(vision, imageGen, tts, preprocessor, imageFetcher, chatService, sceneDetector);
    }

    private AgentMessage msg(MediaType type, String text, byte[] bytes) {
        return new AgentMessage(MessageSource.WECHAT, "u1", text, type, bytes, "m1", Instant.now());
    }

    // 图片消息 → 先回"已收到"即时反馈 → 识图 → 回复描述
    @Test
    void imageMessage_returnsDescription() throws Exception {
        when(vision.analyzeImage(any(), any(), any())).thenReturn("一只橘猫");

        String reply = handler.handle(msg(MediaType.IMAGE, "", new byte[]{1, 2, 3}), adapter);

        assertTrue(reply.contains("一只橘猫"));
        verify(adapter).send("u1", "已收到图片，正在识别，请稍候…");
        verify(chatService, never()).chat(anyString(), anyString(), anyString());
    }

    // 语音消息 → 转写成功 → 当用户文本走正常对话
    @Test
    void voiceMessage_transcribesThenChats() throws Exception {
        when(preprocessor.voiceToText(any())).thenReturn("我想在北京租房");
        when(sceneDetector.detect("我想在北京租房")).thenReturn(Scene.CHAT);
        when(chatService.chat("我想在北京租房", "u1", "CHAT")).thenReturn("好的，预算多少？");

        String reply = handler.handle(msg(MediaType.VOICE, "", new byte[]{1, 2, 3}), adapter);

        assertEquals("好的，预算多少？", reply);
        verify(chatService).chat("我想在北京租房", "u1", "CHAT");
    }

    // 语音转写失败 → 降级话术原样回
    @Test
    void voiceMessage_transcribeFail_returnsHelp() throws Exception {
        when(preprocessor.voiceToText(any())).thenReturn("收到语音，但未能识别出文字，请发文字");

        String reply = handler.handle(msg(MediaType.VOICE, "", new byte[]{1, 2, 3}), adapter);

        assertTrue(reply.contains("未能识别出文字"));
        verify(chatService, never()).chat(anyString(), anyString(), anyString());
    }

    // 创作场景文本 → 先回"已收到"即时反馈 → 文生图 → 下载发图 → 返回空串（已处理完）
    @Test
    void creationText_generatesAndSendsImage() throws Exception {
        when(sceneDetector.detect("画一只猫")).thenReturn(Scene.CREATION);
        when(imageGen.generate("画一只猫")).thenReturn("https://img.example.com/cat.png");
        when(imageFetcher.fetch("https://img.example.com/cat.png")).thenReturn(new byte[]{9, 9, 9});

        String reply = handler.handle(msg(MediaType.TEXT, "画一只猫", null), adapter);

        assertEquals("", reply, "已发图，空串表示处理完毕");
        verify(adapter).send("u1", "已收到，正在为你生成图片，请稍候…");   // 长任务即时反馈（wanx 生成几十秒）
        verify(adapter).sendImage("u1", new byte[]{9, 9, 9}, "creation.png", "");
    }

    // 文生图失败 → 降级话术
    @Test
    void creationText_generateFail_returnsHelp() throws Exception {
        when(sceneDetector.detect("画一只猫")).thenReturn(Scene.CREATION);
        when(imageGen.generate("画一只猫")).thenReturn("图片生成失败: API 挂了");

        String reply = handler.handle(msg(MediaType.TEXT, "画一只猫", null), adapter);

        assertTrue(reply.contains("图片生成失败"));
    }

    // 纯文本非创作 → null 放行进正常对话
    @Test
    void plainText_returnsNull() throws Exception {
        when(sceneDetector.detect("你好")).thenReturn(Scene.CHAT);

        assertNull(handler.handle(msg(MediaType.TEXT, "你好", null), adapter));
    }

    // #tts 内容 → 先回"已收到"即时反馈 → 合成语音发出（TTS 能力入口），返回空串表示已处理
    @Test
    void ttsCommand_synthesizesAndSendsVoice() throws Exception {
        when(tts.synthesize("你好，欢迎使用星链智核", null)).thenReturn(new byte[]{7, 7, 7});

        String reply = handler.handle(msg(MediaType.TEXT, "#tts 你好，欢迎使用星链智核", null), adapter);

        assertEquals("", reply, "已发语音");
        verify(adapter).send("u1", "已收到，正在合成语音，请稍候…");
        verify(adapter).sendVoice("u1", new byte[]{7, 7, 7}, "tts.wav");
    }

    // TTS 失败 → 降级话术
    @Test
    void ttsCommand_synthesizeFail_returnsHelp() throws Exception {
        when(tts.synthesize(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(null);

        String reply = handler.handle(msg(MediaType.TEXT, "#tts 你好", null), adapter);

        assertTrue(reply.contains("语音合成失败"));
        verify(adapter, never()).sendVoice(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }
}