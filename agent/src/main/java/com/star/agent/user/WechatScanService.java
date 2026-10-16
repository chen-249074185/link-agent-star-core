package com.star.agent.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 微信扫码：Web 端展示"接入微信"二维码 + 状态轮询
// 直接调 SDK 反编译确认的官方 HTTP 接口（SDK 内部 LoginService 即这两个 URL，私有字段绕不过去）：
//   GET https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3   → {qrcode, qrcodeImgContent}
//   GET https://ilinkai.weixin.qq.com/ilink/bot/get_qrcode_status?qrcode=xx → {status, botToken, botId, userId, baseUrl}
// 状态流转：WAITING(等待) → SCANNED(已扫待确认) → CONFIRMED(确认=登录成功) / EXPIRED(过期)
// 扫码确认后拿到的是"机器人宿主微信"身份（botId/userId）——绑定流程把它写入 users.wechat_id
// 注意：扫码只做"身份确认 + 绑定"，不启动 Bot（Bot 上线仍走 BotManager 的 #newbot/重启恢复）
@Component
public class WechatScanService {

    private static final Logger logger = LoggerFactory.getLogger(WechatScanService.class);

    private static final String QRCODE_URL = "https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3";
    private static final String STATUS_URL = "https://ilinkai.weixin.qq.com/ilink/bot/get_qrcode_status?qrcode=";
    // 扫码会话有效期：二维码本身约 3 分钟过期，这里给足窗口
    private static final Duration SCAN_TTL = Duration.ofMinutes(5);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)   // 官方服务器 HTTP/2 协商会卡死（实测），强制 HTTP/1.1
            .build();
    private final String qrcodeUrl;
    private final String statusUrl;

    public WechatScanService() {
        this(QRCODE_URL, STATUS_URL);
    }

    // 测试构造器：本地假服务器模拟官方接口
    WechatScanService(String qrcodeUrl, String statusUrl) {
        this.qrcodeUrl = qrcodeUrl;
        this.statusUrl = statusUrl;
    }

    // ticket → 扫码会话（qrcode 码串 + 图片内容）；确认后即失效
    private final Map<String, ScanSession> sessions = new ConcurrentHashMap<>();

    // 一次扫码会话：ticket 是前端轮询的凭证，qrcode 是状态查询码，imgContent 是可扫内容（官方 liteapp 链接）
    public record ScanSession(String ticket, String qrcode, String imgContent, long createdAt) {
    }

    // 状态快照：status 原始值（WAITING/SCANNED/CONFIRMED/EXPIRED）+ 确认后的登录上下文
    public record ScanStatus(String status, boolean confirmed, String botToken, String botId,
                             String userId, String baseUrl) {
    }

    // 发起一次扫码：拉官方二维码，登记会话，返回给前端展示
    public ScanSession startScan() {
        try {
            JsonNode json = getJson(qrcodeUrl);
            // 键名兼容：官方返回下划线 qrcode_img_content（SDK 内部 serializer 才做驼峰映射，直连需自己兼容）
            String qrcode = json.path("qrcode").asText();
            String imgContent = firstText(json, "qrcodeImgContent", "qrcode_img_content");
            if (qrcode.isEmpty()) {
                throw new IllegalStateException("微信扫码接口返回异常: " + json);
            }
            ScanSession session = new ScanSession(UUID.randomUUID().toString().substring(0, 8),
                    qrcode, imgContent, System.currentTimeMillis());
            sessions.put(session.ticket(), session);
            logger.info("微信扫码会话发起: ticket={}", session.ticket());
            return session;
        } catch (Exception e) {
            throw new IllegalStateException("获取微信二维码失败: " + e.getMessage(), e);
        }
    }

    // 二维码图片（本地生成，10-31 修复）：官方 imgContent 是 liteapp 网页链接（img 加载 HTML 慢且显示不出），
    // 把该链接内容画成二维码 PNG（ZXing），返回 data URL 一次到位——照老项目"后端出图"思路但不依赖海外画图服务
    public String qrcodeDataUrl(String ticket) {
        ScanSession session = sessions.get(ticket);
        if (session == null || session.imgContent() == null || session.imgContent().isBlank()) {
            return null;
        }
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(session.imgContent(), BarcodeFormat.QR_CODE, 300, 300);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            logger.warn("二维码图片生成失败: {}", e.getMessage());
            return null;
        }
    }

    // 轮询扫码状态（前端每 8s 调一次）：官方 status 是长轮询——未扫码挂起 ~30s 返回 wait，
    // 扫码确认/过期则秒回；挂起期间的等待由官方承担，前端定期来取一次结果即可
    public ScanStatus status(String ticket) {
        ScanSession session = sessions.get(ticket);
        if (session == null) {
            return null;
        }
        if (System.currentTimeMillis() - session.createdAt() > SCAN_TTL.toMillis()) {
            sessions.remove(ticket);
            return null;
        }
        try {
            JsonNode json = getJson(statusUrl + session.qrcode());
            // 官方原始状态值：wait / scaned|scanned / confirmed / expired（小写，SDK 源码确认）
            // 统一规范化为大写枚举给前端判断，避免前后端拼写漂移
            String status = normalizeStatus(json.path("status").asText("wait"));
            boolean confirmed = "CONFIRMED".equals(status);
            // 键名兼容：官方下划线格式；身份字段 SDK 源码确认是 ilink_bot_id/ilink_user_id/baseurl（不是 bot_id/user_id）
            ScanStatus result = new ScanStatus(status, confirmed,
                    firstText(json, "botToken", "bot_token"),
                    firstText(json, "botId", "ilink_bot_id"),
                    firstText(json, "userId", "ilink_user_id"),
                    firstText(json, "baseUrl", "baseurl"));
            if (confirmed) {
                logger.info("微信扫码确认: botId={} userId={}", result.botId(), result.userId());
            }
            return result;
        } catch (Exception e) {
            logger.warn("轮询微信扫码状态失败: {}", e.getMessage());
            return new ScanStatus("ERROR", false, null, null, null, null);
        }
    }

    // 官方状态值 → 规范化枚举（wait→WAITING，scaned/scanned→SCANNED，confirmed→CONFIRMED，expired→EXPIRED）
    static String normalizeStatus(String raw) {
        String s = raw == null ? "wait" : raw.toLowerCase();
        if (s.startsWith("confirm")) return "CONFIRMED";
        if (s.startsWith("scan")) return "SCANNED";
        if (s.startsWith("expire")) return "EXPIRED";
        return "WAITING";
    }

    // 键名兼容：驼峰优先、下划线兜底（官方接口下划线，SDK 才做驼峰映射）；缺失返回 null
    private String firstText(JsonNode node, String camel, String snake) {
        String v = node.path(camel).asText("");
        if (v.isEmpty()) {
            v = node.path(snake).asText("");
        }
        return v.isEmpty() ? null : v;
    }

    // 绑定成功后消费会话：同一 ticket 只允许绑定一次（防重复提交）
    public void consume(String ticket) {
        sessions.remove(ticket);
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                // 官方 status 接口是长轮询：未扫码时挂起 ~30s 才返回 wait（扫码确认则秒回）
                // 超时必须大于挂起时长，否则每次都超时误判 ERROR
                .timeout(Duration.ofSeconds(40))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body());
    }
}