package com.star.agent.scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// SceneDetector 三层识别串联单测：③手动覆盖 → ①关键词快筛 → ②LLM 兜底
// 用 Mockito mock IntentClassifier（不真打 qwen-turbo）
@ExtendWith(MockitoExtension.class)
class SceneDetectorTest {

    @Mock
    private IntentClassifier intentClassifier;

    private final SceneDetector detector = new SceneDetector();

    // ============ 第①层：关键词快筛（旧用例保持） ============

    @Test
    void creationMessages() {
        assertEquals(Scene.CREATION, detector.detect("帮我画一只猫"));
        assertEquals(Scene.CREATION, detector.detect("写首诗"));
        assertEquals(Scene.CREATION, detector.detect("帮我画一朵花"));
        assertEquals(Scene.CREATION, detector.detect("给我编个故事"));
    }

    @Test
    void officeMessages() {
        assertEquals(Scene.OFFICE, detector.detect("帮我写周报"));
        assertEquals(Scene.OFFICE, detector.detect("做一份会议纪要"));
        assertEquals(Scene.OFFICE, detector.detect("帮我做表格"));
        assertEquals(Scene.OFFICE, detector.detect("写邮件给老板"));
    }

    @Test
    void dataMessages() {
        assertEquals(Scene.DATA, detector.detect("今天上海天气怎么样"));
        assertEquals(Scene.DATA, detector.detect("算一下3+5"));
        assertEquals(Scene.DATA, detector.detect("宁德时代股价多少"));
        assertEquals(Scene.DATA, detector.detect("美元汇率是多少"));
    }

    @Test
    void chatMessages() {
        assertEquals(Scene.CHAT, detector.detect("你好"));
        assertEquals(Scene.CHAT, detector.detect("在吗"));
        assertEquals(Scene.CHAT, detector.detect("谢谢"));
        assertEquals(Scene.CHAT, detector.detect("你是谁"));
    }

    @Test
    void groupMessage() {
        assertEquals(Scene.GROUP, detector.detect("@全体成员 下午开会"));
    }

    // ============ 第③层：手动覆盖（优先级最高） ============

    // /scene:DATA 带闲聊词"你好" → 手动覆盖压过关键词，返回 DATA
    @Test
    void manualOverrideWinsOverKeywords() {
        SceneDetector springDetector = new SceneDetector(intentClassifier);
        assertEquals(Scene.DATA, springDetector.detect("/scene:DATA 你好"));
        verify(intentClassifier, never()).classify(anyList());
    }

    // 非法场景名（/scene:abc）→ 不崩，落到关键词层
    @Test
    void manualOverrideInvalidFallsThroughToKeywords() {
        SceneDetector springDetector = new SceneDetector(intentClassifier);
        assertEquals(Scene.DATA, springDetector.detect("/scene:abc 今天上海天气"));
        verify(intentClassifier, never()).classify(anyList());
    }

    // ============ 第②层：LLM 兜底 ============

    // 关键词命中 → 不花钱请 LLM
    @Test
    void keywordHitSkipsLlm() {
        SceneDetector springDetector = new SceneDetector(intentClassifier);
        assertEquals(Scene.DATA, springDetector.detect("今天上海天气"));
        verify(intentClassifier, never()).classify(anyList());
    }

    // 关键词未命中 → LLM 兜底，返回 LLM 的判定
    @Test
    void keywordMissFallsBackToLlm() {
        when(intentClassifier.classify(anyList())).thenReturn(Scene.CREATION);
        SceneDetector springDetector = new SceneDetector(intentClassifier);
        assertEquals(Scene.CREATION, springDetector.detect("帮我想个生日祝福文案"));
        verify(intentClassifier).classify(anyList());
    }

    // 手动链路（无 LLM 层）：关键词未命中 → 直接兜底 CHAT，不崩
    @Test
    void noLlmLayerFallsBackToChat() {
        assertEquals(Scene.CHAT, detector.detect("随便聊聊"));
        assertEquals(Scene.CHAT, detector.detect(""));
        assertEquals(Scene.CHAT, detector.detect(null));
    }

    // 空消息/空文本 → 直接 CHAT，连 LLM 都不碰
    @Test
    void blankTextSkipsAllLayers() {
        SceneDetector springDetector = new SceneDetector(intentClassifier);
        assertEquals(Scene.CHAT, springDetector.detect(""));
        assertEquals(Scene.CHAT, springDetector.detect(null));
        verify(intentClassifier, never()).classify(anyList());
    }

    private static List<String> anyList() {
        return org.mockito.ArgumentMatchers.anyList();
    }
}