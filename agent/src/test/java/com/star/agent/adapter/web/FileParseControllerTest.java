package com.star.agent.adapter.web;

import com.star.agent.skill.impl.PoiDocParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// FileParseController 端点测试：上传解析成功返回文本 + 不支持类型降级话术
@WebMvcTest(FileParseController.class)
class FileParseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PoiDocParser poiDocParser;

    @Test
    void parse_docxSuccessReturnsText() throws Exception {
        when(poiDocParser.parse(any(), eq("docx"))).thenReturn("# 简历\n姓名：张三");

        MockMultipartFile file = new MockMultipartFile("file", "简历.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "x".getBytes());

        mockMvc.perform(multipart("/api/file/parse")
                        .file(file)
                        .param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.filename").value("简历.docx"))
                .andExpect(jsonPath("$.data.text").value("# 简历\n姓名：张三"))
                .andExpect(jsonPath("$.data.chars").value(10));

        verify(poiDocParser).parse(any(), eq("docx"));
    }

    // txt 直读不劳烦解析器
    @Test
    void parse_txtReadsDirectly() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "笔记.txt",
                MediaType.TEXT_PLAIN_VALUE, "北京租房笔记".getBytes());

        mockMvc.perform(multipart("/api/file/parse")
                        .file(file)
                        .param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.text").value("北京租房笔记"));

        verify(poiDocParser, org.mockito.Mockito.never()).parse(any(), any());
    }

    @Test
    void parse_unsupportedReturnsFriendlyMsg() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "恶意.exe",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, "x".getBytes());

        mockMvc.perform(multipart("/api/file/parse")
                        .file(file)
                        .param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("仅支持")));
    }
}