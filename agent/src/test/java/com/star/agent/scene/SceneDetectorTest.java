package com.star.agent.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SceneDetectorTest {

    private final SceneDetector detector = new SceneDetector();

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

    @Test
    void fallbackToChat() {
        assertEquals(Scene.CHAT, detector.detect("随便聊聊"));
        assertEquals(Scene.CHAT, detector.detect(""));
        assertEquals(Scene.CHAT, detector.detect(null));
    }
}