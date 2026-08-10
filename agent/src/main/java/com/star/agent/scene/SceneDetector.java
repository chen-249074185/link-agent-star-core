package com.star.agent.scene;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SceneDetector {

    // 规则表：场景名 -> 关键词列表（LinkedHashMap 保证按 YAML 里的顺序）
    private final Map<String, List<String>> rules = new LinkedHashMap<>();

    public SceneDetector() {
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

    //调用的是写好的rules
    public Scene detect(String text) {
        if (text == null || text.isBlank()) {
            return Scene.CHAT;
        }
        // 按 YAML 里场景的顺序，第一个命中的优先
        for (Map.Entry<String, List<String>> e : rules.entrySet()) {
            for (String keyword : e.getValue()) {
                if (text.contains(keyword)) {
                    return Scene.valueOf(e.getKey());
                }
            }
        }
        return Scene.CHAT; // 兜底：都没命中 → 闲聊
    }
}