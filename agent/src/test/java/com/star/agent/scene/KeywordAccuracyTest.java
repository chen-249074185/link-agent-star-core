package com.star.agent.scene;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

// 关键词层准确率评测：50 条标注消息 → 无参 SceneDetector（纯关键词，LLM 兜底由微信实测）
// DoD：准确率 ≥90%（计划书 09-12 验收标准）
class KeywordAccuracyTest {

    // 评测集：50 条（每场景 10 条），标注为消息 → 期望场景
    private static final Map<String, Scene> EVAL_SET = new LinkedHashMap<>();

    static {
        // CREATION 10 条
        EVAL_SET.put("帮我画一只猫", Scene.CREATION);
        EVAL_SET.put("画个风景", Scene.CREATION);
        EVAL_SET.put("帮我写首诗", Scene.CREATION);
        EVAL_SET.put("编个科幻故事", Scene.CREATION);
        EVAL_SET.put("写一篇 800 字作文", Scene.CREATION);
        EVAL_SET.put("生成一张海报", Scene.CREATION);
        EVAL_SET.put("帮我设计个 logo", Scene.CREATION);
        EVAL_SET.put("写首歌的歌词", Scene.CREATION);
        EVAL_SET.put("给童话故事写个续集", Scene.CREATION);
        EVAL_SET.put("画一张漫画分镜", Scene.CREATION);
        // OFFICE 10 条
        EVAL_SET.put("帮我写周报", Scene.OFFICE);
        EVAL_SET.put("整理一下月报数据", Scene.OFFICE);
        EVAL_SET.put("做一份会议纪要", Scene.OFFICE);
        EVAL_SET.put("帮我把数据做成表格", Scene.OFFICE);
        EVAL_SET.put("写封邮件给客户", Scene.OFFICE);
        EVAL_SET.put("做一个工作汇报 PPT", Scene.OFFICE);
        EVAL_SET.put("排一下下周的工作计划", Scene.OFFICE);
        EVAL_SET.put("写份项目提案", Scene.OFFICE);
        EVAL_SET.put("把会议议程发给我", Scene.OFFICE);
        EVAL_SET.put("写个活动方案", Scene.OFFICE);
        // DATA 10 条
        EVAL_SET.put("今天上海天气怎么样", Scene.DATA);
        EVAL_SET.put("明天会下雨吗", Scene.DATA);
        EVAL_SET.put("宁德时代股价多少", Scene.DATA);
        EVAL_SET.put("美元兑人民币汇率", Scene.DATA);
        EVAL_SET.put("算一下 3+5 等于几", Scene.DATA);
        EVAL_SET.put("现在几点了", Scene.DATA);
        EVAL_SET.put("查一下快递到哪了", Scene.DATA);
        EVAL_SET.put("今天新闻头条是什么", Scene.DATA);
        EVAL_SET.put("油价今天多少钱", Scene.DATA);
        EVAL_SET.put("查查北京到上海多少公里", Scene.DATA);
        // CHAT 10 条
        EVAL_SET.put("你好", Scene.CHAT);
        EVAL_SET.put("在吗", Scene.CHAT);
        EVAL_SET.put("谢谢啦", Scene.CHAT);
        EVAL_SET.put("讲个笑话呗", Scene.CHAT);
        EVAL_SET.put("吃饭了吗", Scene.CHAT);
        EVAL_SET.put("最近好累啊", Scene.CHAT);
        EVAL_SET.put("今天心情不错", Scene.CHAT);
        EVAL_SET.put("晚安", Scene.CHAT);
        EVAL_SET.put("随便聊聊", Scene.CHAT);
        EVAL_SET.put("有空吗", Scene.CHAT);
        // GROUP 10 条
        EVAL_SET.put("@全体成员 下午开会", Scene.GROUP);
        EVAL_SET.put("@所有人 记得交周报", Scene.GROUP);
        EVAL_SET.put("@全员 明天团建", Scene.GROUP);
        EVAL_SET.put("大家在群里吗", Scene.GROUP);
        EVAL_SET.put("各位群友晚上好", Scene.GROUP);
        EVAL_SET.put("麻烦全员看下通知", Scene.GROUP);
        EVAL_SET.put("@全体成员 注意安全", Scene.GROUP);
        EVAL_SET.put("@所有人 会议改期", Scene.GROUP);
        EVAL_SET.put("大家有什么想法吗", Scene.GROUP);
        EVAL_SET.put("各位注意截止时间", Scene.GROUP);
    }

    // DoD 验收：50 条评测集，关键词层准确率 ≥90%
    @Test
    void keywordLayerAccuracyAtLeast90Percent() {
        SceneDetector detector = new SceneDetector();
        int hit = 0;
        StringBuilder missLog = new StringBuilder();
        for (Map.Entry<String, Scene> e : EVAL_SET.entrySet()) {
            Scene actual = detector.detect(e.getKey());
            if (actual == e.getValue()) {
                hit++;
            } else {
                missLog.append("  [").append(e.getKey()).append("] 期望 ").append(e.getValue())
                        .append(" 实际 ").append(actual).append('\n');
            }
        }
        double rate = hit * 100.0 / EVAL_SET.size();
        System.out.printf("### 关键词层准确率: %.1f%% (%d/%d)%n", rate, hit, EVAL_SET.size());
        System.out.println("### 未命中:\n" + missLog);
        assertTrue(rate >= 90, "准确率 " + rate + "% 低于 90%，未命中:\n" + missLog);
    }
}