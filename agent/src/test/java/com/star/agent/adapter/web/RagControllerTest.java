package com.star.agent.adapter.web;

import com.star.agent.mcp.McpConnection;
import com.star.agent.skill.impl.PoiDocParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// RagController 端点测试：upload/list/delete/search 参数映射 + 503 降级
@WebMvcTest(RagController.class)
class RagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private McpConnection connection;

    @MockBean
    private PoiDocParser poiDocParser;

    // 上传：multipart 文件 → base64 转 MCP rag_upload（is_base64=true，kbId 缺省默认库）
    @Test
    void upload_forwardsBase64ToMcp() throws Exception {
        when(connection.callTool(eq("rag_upload"), anyMap())).thenReturn("已入库 2 块");

        MockMultipartFile file = new MockMultipartFile("file", "租房笔记.txt",
                MediaType.TEXT_PLAIN_VALUE, "北京租房指南。\n\n上海租房指南。".getBytes());

        mockMvc.perform(multipart("/api/rag/upload")
                        .file(file)
                        .param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("已入库 2 块"));

        verify(connection).callTool(eq("rag_upload"), org.mockito.ArgumentMatchers.argThat(args ->
                "u1".equals(args.get("user_id"))
                        && "u1-default".equals(args.get("kb_id"))
                        && Boolean.TRUE.equals(args.get("is_base64"))
                        && args.get("filename").equals("租房笔记.txt")
                        && args.get("content") instanceof String s && !s.isEmpty()));
    }

    // docx 上传：POI 纯 Java 解析 → 当 .md 文档入库（11-02：POI 主通道，不碰 anydoc 子进程）
    @Test
    void upload_docx_convertedToMarkdown() throws Exception {
        when(poiDocParser.parse(any(), eq("docx"))).thenReturn("# 租房报告\n\n北京房源整理。");
        when(connection.callTool(eq("rag_upload"), anyMap())).thenReturn("已入库 2 块");

        MockMultipartFile file = new MockMultipartFile("file", "租房报告.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "假 docx 二进制".getBytes());

        mockMvc.perform(multipart("/api/rag/upload")
                        .file(file)
                        .param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("已入库 2 块"));

        verify(connection).callTool(eq("rag_upload"), org.mockito.ArgumentMatchers.argThat(args ->
                "租房报告.md".equals(args.get("filename"))
                        && "# 租房报告\n\n北京房源整理。".equals(new String(
                        Base64.getDecoder().decode((String) args.get("content")), StandardCharsets.UTF_8))
                        && Boolean.TRUE.equals(args.get("is_base64"))));
        verify(poiDocParser).parse(any(), eq("docx"));
    }

    // POI 解析失败（损坏文件）→ 400，不把半截内容入库
    @Test
    void upload_docxParseFail_rejected() throws Exception {
        when(poiDocParser.parse(any(), eq("docx"))).thenReturn(null);

        MockMultipartFile file = new MockMultipartFile("file", "坏文件.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "坏文件".getBytes());

        mockMvc.perform(multipart("/api/rag/upload")
                        .file(file)
                        .param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("文档解析失败")));

        verify(connection, org.mockito.Mockito.never()).callTool(any(), any());
    }

    // doc 老格式（POI 不支持）→ 明确 400 话术（anydoc 已移除，11-02）
    @Test
    void upload_doc_rejectedWithFriendlyMsg() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "简历.doc",
                "application/msword", "假 doc 二进制".getBytes());

        mockMvc.perform(multipart("/api/rag/upload")
                        .file(file)
                        .param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("不支持的文件类型")));

        verify(connection, org.mockito.Mockito.never()).callTool(any(), any());
    }

    // 完全不受支持的类型（exe，解析器都不认）→ 400 友好话术，不调 rag-mcp
    @Test
    void upload_unsupportedExt_rejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "evil.exe",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, "exe".getBytes());

        mockMvc.perform(multipart("/api/rag/upload")
                        .file(file)
                        .param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("不支持的文件类型")));

        verify(connection, org.mockito.Mockito.never()).callTool(any(), any());
    }

    // list：透传 userId 调 rag_list_kbs
    @Test
    void list_returnsKbs() throws Exception {
        when(connection.callTool(eq("rag_list_kbs"), anyMap())).thenReturn("u1-default | 默认知识库");

        mockMvc.perform(get("/api/rag/list").param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("u1-default | 默认知识库"));

        verify(connection).callTool(eq("rag_list_kbs"),
                org.mockito.ArgumentMatchers.argThat(args -> "u1".equals(args.get("user_id"))));
    }

    // delete：透传 userId+kbId
    @Test
    void delete_removesKb() throws Exception {
        when(connection.callTool(eq("rag_delete_kb"), anyMap())).thenReturn("已删除知识库: kb1");

        mockMvc.perform(delete("/api/rag/delete").param("userId", "u1").param("kbId", "kb1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("已删除知识库: kb1"));

        verify(connection).callTool(eq("rag_delete_kb"),
                org.mockito.ArgumentMatchers.argThat(args ->
                        "u1".equals(args.get("user_id")) && "kb1".equals(args.get("kb_id"))));
    }

    // files（分文件列表）：kbId 缺省默认库，透传调 rag_list_files
    @Test
    void files_listsDefaultKbFiles() throws Exception {
        when(connection.callTool(eq("rag_list_files"), anyMap())).thenReturn("笔记.txt | 3 块 | 2026-11-02 10:00:00");

        mockMvc.perform(get("/api/rag/files").param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(connection).callTool(eq("rag_list_files"),
                org.mockito.ArgumentMatchers.argThat(args ->
                        "u1".equals(args.get("user_id")) && "u1-default".equals(args.get("kb_id"))));
    }

    // delete-file（分文件删除）：透传 userId+kbId+filename
    @Test
    void deleteFile_removesSingleFile() throws Exception {
        when(connection.callTool(eq("rag_delete_file"), anyMap())).thenReturn("已删除文件: 笔记.txt（3 块）");

        mockMvc.perform(delete("/api/rag/delete-file")
                        .param("userId", "u1")
                        .param("filename", "笔记.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("已删除文件: 笔记.txt（3 块）"));

        verify(connection).callTool(eq("rag_delete_file"),
                org.mockito.ArgumentMatchers.argThat(args ->
                        "u1".equals(args.get("user_id"))
                                && "u1-default".equals(args.get("kb_id"))
                                && "笔记.txt".equals(args.get("filename"))));
    }

    // search：kbId 缺省默认库 + topK 数值保真
    @Test
    void search_usesDefaultKbAndTopK() throws Exception {
        when(connection.callTool(eq("rag_search"), anyMap())).thenReturn("[score=0.1] 北京房源");

        mockMvc.perform(get("/api/rag/search")
                        .param("userId", "u1")
                        .param("query", "北京")
                        .param("topK", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(connection).callTool(eq("rag_search"),
                org.mockito.ArgumentMatchers.argThat(args ->
                        "u1-default".equals(args.get("kb_id"))
                                && "北京".equals(args.get("query"))
                                && args.get("top_k").equals(3)));
    }

    // MCP 调用抛异常 → 500 + 错误信息（不 500 崩溃）
    @Test
    void callFailure_returnsErrorResult() throws Exception {
        when(connection.callTool(eq("rag_search"), anyMap())).thenThrow(new RuntimeException("连接断开"));

        mockMvc.perform(get("/api/rag/search").param("userId", "u1").param("query", "北京"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("连接断开")));
    }
}