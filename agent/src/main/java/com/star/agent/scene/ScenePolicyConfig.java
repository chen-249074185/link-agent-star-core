package com.star.agent.scene;

import com.star.agent.skill.SkillRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

//菜谱
@Component
@ConfigurationProperties(prefix = "linkagent.scene-policy")
public class ScenePolicyConfig {

    private static final Logger logger = LoggerFactory.getLogger(ScenePolicyConfig.class);

    // 注意：不能用"唯一构造器注入"依赖（坑26）！
    // @ConfigurationProperties 类只有一个构造器时，Spring 推断成"构造器绑定"模式，
    // 会把构造器参数当配置属性绑 → setPolicies() 永不执行 → 配置绑空。
    // 官方 corner case 解法：setter 注入。
    private SkillRegistry skillRegistry;

    // 绑定目标：yml 里那张表会整个塞进这个 Map（场景→策略）
    private Map<Scene, ScenePolicy> policies = Map.of();

    // setter 注入 SkillRegistry，用于启动校验工具名是否真实存在
    @Autowired
    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    // setter：Spring 绑定配置时调用它（把解析好的 Map 塞进来）
    public void setPolicies(Map<Scene, ScenePolicy> policies) {
        this.policies = policies;
    }

    // 给别人读的表
    public Map<Scene, ScenePolicy> getPolicies() {
        return policies;
    }

    // 启动校验：@PostConstruct = bean 创建完成、配置绑定好后自动跑一次
    @PostConstruct
    public void validate() {
        for (Scene scene : Scene.values()) {
            ScenePolicy p = policies.get(scene);
            if (p == null) {
                throw new IllegalStateException("场景策略缺失: " + scene + "，请在 application.yml 补齐");
            }
            if (p.model() == null || p.model().isBlank()) {
                throw new IllegalStateException("场景 " + scene + " 的 model 未配置");
            }
            for (String tool : p.tools()) {
                if (skillRegistry.getSkill(tool) == null) {
                    throw new IllegalStateException("场景 " + scene + " 的工具白名单含未知技能: " + tool);
                }
            }
        }
        logger.info("场景策略校验通过(共{}个场景): {}", policies.size(), policies.keySet());
    }
}