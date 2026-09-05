package com.star.agent.infra.web;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 请求级 traceId 过滤器单测（纯 mock 对象，不拉 Spring 容器）：
// 处理期间 MDC 有值（链内断言）→ 响应头回传 → filter 返回后已清理；有头沿用；异常路径也清理
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.remove("traceId");
    }

    @Test
    void noHeader_generatesTraceIdInChainAndEchoesBack() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/chat/ask?message=hi");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                // 链内=请求处理中：MDC 应已有 8 位 traceId（logback [%X{traceId}] 打印用）
                String inChain = MDC.get("traceId");
                assertNotNull(inChain);
                assertEquals(8, inChain.length());
            }
        });

        // filter 返回后：响应头回传了同一个 id + MDC 已清理干净
        String traceId = resp.getHeader(TraceIdFilter.HEADER);
        assertNotNull(traceId);
        assertEquals(8, traceId.length());
        assertNull(MDC.get("traceId"));
    }

    @Test
    void withHeader_reusesIt() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/chat/ask");
        req.addHeader(TraceIdFilter.HEADER, "abc12345");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                assertEquals("abc12345", MDC.get("traceId"));
            }
        });

        assertEquals("abc12345", resp.getHeader(TraceIdFilter.HEADER));
    }

    // 关键：finally 清理——请求结束 MDC 必须清干净，否则线程池复用下一条请求会串 traceId
    @Test
    void afterFilter_mdcCleared() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(MDC.get("traceId"));
    }

    // 链路抛异常（如业务 500）→ 同样要清理，不能留脏
    @Test
    void exceptionInChain_mdcStillCleared() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        MockFilterChain exploding = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                throw new RuntimeException("boom");
            }
        };

        assertThrows(RuntimeException.class,
                () -> filter.doFilter(req, new MockHttpServletResponse(), exploding));
        assertNull(MDC.get("traceId"));
    }
}