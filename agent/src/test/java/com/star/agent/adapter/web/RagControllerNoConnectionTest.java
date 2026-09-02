package com.star.agent.adapter.web;

import com.star.agent.skill.impl.PoiDocParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 无 McpConnection bean（mock 模式）时：RAG 接口 503 降级，不 500 崩溃
// 注意：本测试类不 @MockBean McpConnection，@WebMvcTest 也不加载 @ConditionalOnProperty(false) 的组件
@WebMvcTest(RagController.class)
class RagControllerNoConnectionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PoiDocParser poiDocParser;   // RagController 构造依赖（docx 转换），切片测试要补 mock

    @Test
    void noConnection_returns503() throws Exception {
        mockMvc.perform(get("/api/rag/search").param("userId", "u1").param("query", "北京"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("暂不可用")));
    }
}