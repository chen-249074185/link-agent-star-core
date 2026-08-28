package com.star.agent.user;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// WechatScanService 单测：本地假服务器模拟官方 get_bot_qrcode / get_qrcode_status 接口
class WechatScanServiceTest {

    // 按路径分发：get_bot_qrcode → 二维码 JSON；get_qrcode_status → 状态 JSON
    private HttpServer startServer(String statusBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = path.contains("get_qrcode_status")
                    ? statusBody
                    : "{\"qrcode\":\"qr-1\",\"qrcodeImgContent\":\"data:image/png;base64,xxx\"}";
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        return server;
    }

    private WechatScanService newService(HttpServer server) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new WechatScanService(base + "/get_bot_qrcode?bot_type=3", base + "/get_qrcode_status?qrcode=");
    }

    // 发起扫码：返回 ticket + 二维码内容，状态从 WAITING 开始
    @Test
    void startScan_returnsTicketAndImg() throws Exception {
        HttpServer server = startServer("{\"status\":\"WAITING\"}");
        try {
            WechatScanService svc = newService(server);

            WechatScanService.ScanSession session = svc.startScan();

            assertNotNull(session.ticket());
            assertEquals("qr-1", session.qrcode());
            assertEquals("data:image/png;base64,xxx", session.imgContent());
        } finally {
            server.stop(0);
        }
    }

    // 状态流转：WAITING → SCANNED → CONFIRMED（确认后带 botId/userId——官方字段名 ilink_bot_id/ilink_user_id/baseurl）
    @Test
    void status_transitionsToConfirmed() throws Exception {
        HttpServer server = startServer("{\"status\":\"confirmed\",\"bot_token\":\"bt\",\"ilink_bot_id\":\"b1\",\"ilink_user_id\":\"wx1@im.wechat\",\"baseurl\":\"url\"}");
        try {
            WechatScanService svc = newService(server);
            WechatScanService.ScanSession session = svc.startScan();

            WechatScanService.ScanStatus st = svc.status(session.ticket());

            assertTrue(st.confirmed());
            assertEquals("CONFIRMED", st.status());
            assertEquals("b1", st.botId());
            assertEquals("wx1@im.wechat", st.userId());
        } finally {
            server.stop(0);
        }
    }

    // 未确认状态：confirmed=false 且不带身份字段
    @Test
    void status_scanned_notConfirmed() throws Exception {
        HttpServer server = startServer("{\"status\":\"SCANNED\"}");
        try {
            WechatScanService svc = newService(server);
            WechatScanService.ScanSession session = svc.startScan();

            WechatScanService.ScanStatus st = svc.status(session.ticket());

            assertFalse(st.confirmed());
            assertEquals("SCANNED", st.status());
            assertNull(st.userId());
        } finally {
            server.stop(0);
        }
    }

    // 无效 ticket → null；consume 后同一 ticket 失效
    @Test
    void status_unknownTicket_returnsNull_andConsumeInvalidates() throws Exception {
        HttpServer server = startServer("{\"status\":\"WAITING\"}");
        try {
            WechatScanService svc = newService(server);

            assertNull(svc.status("ghost"));

            WechatScanService.ScanSession session = svc.startScan();
            svc.consume(session.ticket());
            assertNull(svc.status(session.ticket()));
        } finally {
            server.stop(0);
        }
    }

    // 官方状态值小写（wait/scaned/scanned/confirmed/expired，SDK 源码确认）→ 统一规范化为大写枚举
    @Test
    void normalizeStatus_handlesOfficialValues() {
        assertEquals("WAITING", WechatScanService.normalizeStatus("wait"));
        assertEquals("WAITING", WechatScanService.normalizeStatus("WAIT"));
        assertEquals("SCANNED", WechatScanService.normalizeStatus("scaned"));    // 官方拼写
        assertEquals("SCANNED", WechatScanService.normalizeStatus("scanned"));   // 正确拼写也认
        assertEquals("CONFIRMED", WechatScanService.normalizeStatus("confirmed"));
        assertEquals("EXPIRED", WechatScanService.normalizeStatus("expired"));
        assertEquals("WAITING", WechatScanService.normalizeStatus(null));        // 兜底
    }

    // 官方返回小写 wait → 前端拿到规范 WAITING
    @Test
    void status_officialLowercase_waitIsNormalized() throws Exception {
        HttpServer server = startServer("{\"status\":\"wait\"}");
        try {
            WechatScanService svc = newService(server);
            WechatScanService.ScanSession session = svc.startScan();

            WechatScanService.ScanStatus st = svc.status(session.ticket());

            assertEquals("WAITING", st.status());
            assertFalse(st.confirmed());
        } finally {
            server.stop(0);
        }
    }

    // 二维码图片本地生成：内容=官方 imgContent（liteapp 链接），返回 data URL PNG
    @Test
    void qrcodeDataUrl_generatesLocalPng() throws Exception {
        HttpServer server = startServer("{\"status\":\"wait\"}");
        try {
            WechatScanService svc = newService(server);
            WechatScanService.ScanSession session = svc.startScan();

            String dataUrl = svc.qrcodeDataUrl(session.ticket());

            assertNotNull(dataUrl);
            assertTrue(dataUrl.startsWith("data:image/png;base64,"));
        } finally {
            server.stop(0);
        }
    }

    // 状态接口异常：返回 ERROR 状态不抛（前端可显示重试）；扫码接口异常：抛（端点 500 前端提示）
    @Test
    void status_serverError_returnsErrorNotThrow() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/get_bot_qrcode", exchange -> {
            byte[] resp = "{\"qrcode\":\"qr-1\",\"qrcodeImgContent\":\"img\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.createContext("/get_qrcode_status", exchange -> {
            byte[] resp = "broken".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            WechatScanService svc = new WechatScanService(base + "/get_bot_qrcode?bot_type=3",
                    base + "/get_qrcode_status?qrcode=");
            WechatScanService.ScanSession session = svc.startScan();

            WechatScanService.ScanStatus st = svc.status(session.ticket());

            assertEquals("ERROR", st.status());
            assertFalse(st.confirmed());
        } finally {
            server.stop(0);
        }
    }
}