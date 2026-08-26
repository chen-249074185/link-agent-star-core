package com.star.agent.governance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 敏感工具意图守卫单测：简历等私人信息工具必须用户消息含明确意图关键词
class SensitiveToolGuardTest {

    private final SensitiveToolGuard guard = new SensitiveToolGuard();

    @Test
    void resumeTool_isSensitive() {
        assertTrue(guard.isSensitive("mcp_liepin_resume"));
        assertTrue(guard.isSensitive("mcp_liepin_doctor"), "接入自检也算敏感（寒暄时模型不得主动做）");
        assertFalse(guard.isSensitive("mcp_rag_search"), "普通工具不敏感");
        assertFalse(guard.isSensitive("get_weather"));
    }

    // 明确意图（简历/求职/画像等关键词）→ 放行
    @Test
    void resumeTool_explicitIntent_passes() {
        assertTrue(guard.hasIntent("mcp_liepin_resume", "帮我看看我的简历"));
        assertTrue(guard.hasIntent("mcp_liepin_resume", "我想求职 Java 岗位"));
        assertTrue(guard.hasIntent("mcp_liepin_resume", "查看我的求职画像"));
        assertTrue(guard.hasIntent("mcp_liepin_resume", "帮我完善简历的项目经历"));
    }

    // 寒暄/无关消息 → 拒绝（实测事故：用户说"你好"模型调 resume 展示简历、调 doctor 发自检长文）
    @Test
    void resumeTool_noIntent_denied() {
        assertFalse(guard.hasIntent("mcp_liepin_resume", "你好"));
        assertFalse(guard.hasIntent("mcp_liepin_resume", "北京今天天气怎么样"));
        assertFalse(guard.hasIntent("mcp_liepin_resume", "在杭州租房"));
        assertFalse(guard.hasIntent("mcp_liepin_resume", null));
        assertFalse(guard.hasIntent("mcp_liepin_doctor", "你好"), "寒暄不得触发接入自检");
        assertTrue(guard.hasIntent("mcp_liepin_doctor", "帮我检查一下猎聘接入是否正常"));
    }
}