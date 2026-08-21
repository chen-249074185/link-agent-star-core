package com.star.agent.skill;

import java.util.List;

// 技能编排器：让组合技能（如旅游规划 = 天气 + 通勤 + 计算）内部调用其他技能
// 两条铁律：① 深度上限 2（A→B 止步，禁 A→B→C）② 调用链循环熔断（A→B→A 直接拒，防死循环）
// depth 为当前编排深度（入口传 1），chain 是已走过的技能名清单（含调用方自己）
public interface SkillComposer {

    SkillResult compose(String skillName, String args, SkillContext ctx, int depth, List<String> chain);
}