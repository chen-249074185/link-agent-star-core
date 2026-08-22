package com.star.agent.scene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

//点餐员（三层识别调度器）：
//  detect() 按优先级串联三层——③ /scene:xxx 手动覆盖（用户说了算）→ ① 关键词快筛（零成本）→ ② LLM 意图分类（最后才花钱）
@Component
public class SceneDetector {

    private static final Logger logger = LoggerFactory.getLogger(SceneDetector.class);

    // LLM 兜底层：Spring 注入；手动链路（无参构造器）为 null → 退化成关键词→CHAT
    private final IntentClassifier intentClassifier;

    // 规则表：场景名 -> 关键词列表（LinkedHashMap 保证按 YAML 里的顺序）
    private final Map<String, List<String>> rules = new LinkedHashMap<>();

    // 手动链路（ILinkLoginVerifier 扫码 main 不进 Spring）：无 LLM 兜底
    public SceneDetector() {
        this(null);
    }

    // Spring 用：注入 IntentClassifier
    @Autowired
    public SceneDetector(IntentClassifier intentClassifier) {
        this.intentClassifier = intentClassifier;
        loadRules("/scene-rules.yaml");
    }

    //scene-rules.yaml中的规则对写入rules
    @SuppressWarnings("unchecked")
    private void loadRules(String path) {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getResourceAsStream(path)) {
            Map<String, Object> root = yaml.load(in);
            Map<String, Object> rulesMap = (Map<String, Object>) root.get("rules");

            //rulesMap.entrySet():把里面那层 Map 摊开成一行行（标签+词串）
            for (Map.Entry<String, Object> e : rulesMap.entrySet()) {
                rules.put(e.getKey(), (List<String>) e.getValue());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("加载场景规则失败: " + path, ex);
        }
    }

    // 三层识别入口：③手动覆盖 → ①关键词快筛 → ②LLM 兜底
    public Scene detect(String text) {
        if (text == null || text.isBlank()) {
            return Scene.CHAT;
        }
        Scene manual = parseManualOverride(text);      // ③ 用户说了算，最高优先
        if (manual != null) {
            logger.info("场景[手动覆盖]: {}", manual);
            return manual;
        }
        Scene keyword = detectByKeywords(text);        // ① 零成本快筛，命中即返回
        if (keyword != null) {
            return keyword;
        }
        if (intentClassifier != null) {                // ② 都没中，最后才花钱请 LLM
            Scene llm = intentClassifier.classify(List.of(text));
            logger.info("场景[LLM兜底]: {}", llm);
            return llm;
        }
        return Scene.CHAT;                             // 无 LLM 层（手动链路）→ 兜底闲聊
    }

    // ③ /scene:CHAT → CHAT；/scene:DATA 你好 → 只取第一个词 DATA；
    // 不以 /scene: 开头或场景名非法 → null（交给下一层）
    private Scene parseManualOverride(String text) {
        if (!text.startsWith("/scene:")) {
            return null;
        }
        try {
            String name = text.substring(7).trim();
            int space = name.indexOf(' ');
            if (space > 0) {
                name = name.substring(0, space);   // 只取第一个词，后面是用户消息正文
            }
            return Scene.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.info("场景指令非法: {}", text);
            return null;
        }
    }

    // ① 关键词命中返回场景，未命中返回 null（null = "我没判断出来，交给下一层"）
    private Scene detectByKeywords(String text) {
        for (Map.Entry<String, List<String>> e : rules.entrySet()) {
            for (String keyword : e.getValue()) {
                if (text.contains(keyword)) {
                    return Scene.valueOf(e.getKey());
                }
            }
        }
        return null;
    }
}