package com.star.agent.skill;

import com.star.agent.llm.ToolSpec;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SkillRegistry {

    private final ObjectProvider<Skill> skillObjectProvider; // 供应商：以后要技能找它要

    private final Map<String, Skill> skillMap = new java.util.HashMap<>(); // 收留所有技能的抽屉柜

    private static final Logger logger = LoggerFactory.getLogger(SkillRegistry.class);

    @Autowired
    public SkillRegistry(ObjectProvider<Skill> skillObjectProvider) {
        this.skillObjectProvider = skillObjectProvider;
    }

    // 非 Spring 环境手动搭链路用：直接给技能列表（ILinkLoginVerifier 扫码测试）
    public SkillRegistry(List<Skill> skills) {
        this.skillObjectProvider = null;
        for (Skill skill : skills) {
            String name = skill.meta().name();
            if (skillMap.put(name, skill) != null) {
                throw new IllegalStateException("技能重名: " + name);
            }
        }
    }

    @PostConstruct
    public void init() {
        if (skillObjectProvider == null) return;  // 手动构造（List 版）已直接登记过
        List<String> names = new ArrayList<>();
        for (Skill skill : skillObjectProvider.stream().toList()) {
            String name = skill.meta().name();
            if (skillMap.put(name, skill) != null) {
                throw new IllegalStateException("技能重名: " + name);
            }
            names.add(name);
        }
        logger.info("技能清单(共{}个): {}", names.size(), names);
    }

    public List<ToolSpec> getToolSpecs(){
        List<ToolSpec> specs = new ArrayList<>();
        for(Skill skill : skillMap.values()){
            SkillMeta meta = skill.meta();
            specs.add(new ToolSpec(meta.name(), meta.description(), meta.parameters()));
        }
        return specs;
    }

    public Skill getSkill(String name){
        return skillMap.get(name);
    }

    // 全部已注册技能（本地 @Component + 程序化注册的 MCP 技能都在里面）
    public List<Skill> allSkills() {
        return List.copyOf(skillMap.values());
    }

    // 程序化注册（MCP 技能注册器用）：带重名校验
    public void register(Skill skill) {
        String name = skill.meta().name();
        if (skillMap.put(name, skill) != null) {
            throw new IllegalStateException("技能重名: " + name);
        }
    }

}
