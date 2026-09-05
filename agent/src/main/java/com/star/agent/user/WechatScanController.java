package com.star.agent.user;

import com.star.agent.infra.web.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// 微信扫码（登录页"接入微信"弹窗数据源，W12 10-31）：
//   GET /api/wechat/scan/start   → 发起扫码（未登录可用：JWT 白名单），返回 {ticket, imgContent}
//   GET /api/wechat/scan/status  → 轮询状态（前端每 2s 调）：WAITING/SCANNED/CONFIRMED/EXPIRED
// 确认（CONFIRMED）后由 /api/user/bind-wechat 完成绑定（该端点需登录）
@Tag(name = "微信扫码", description = "登录页接入微信（未登录可用）")
@RestController
@RequestMapping("/api/wechat/scan")
public class WechatScanController {

    private final WechatScanService scanService;

    public WechatScanController(WechatScanService scanService) {
        this.scanService = scanService;
    }

    // 发起扫码：返回 ticket（轮询凭证）+ 本地生成的二维码图片（data URL，前端直接 <img src>，秒开）
    @Operation(summary = "发起微信扫码", description = "返回 {ticket, img(二维码PNG data URL)}")
    @GetMapping("/start")
    public Result<Map<String, String>> start() {
        WechatScanService.ScanSession session = scanService.startScan();
        Map<String, String> data = new java.util.LinkedHashMap<>();
        data.put("ticket", session.ticket());
        data.put("img", scanService.qrcodeDataUrl(session.ticket()));   // 二维码 PNG base64
        return Result.ok(data);
    }

    // 轮询状态：前端拿着 ticket 每 2s 调一次
    @GetMapping("/status")
    public Result<Map<String, Object>> status(@RequestParam String ticket) {
        WechatScanService.ScanStatus status = scanService.status(ticket);
        if (status == null) {
            return Result.fail(404, "扫码会话不存在或已过期，请重新扫码");
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("status", status.status());
        data.put("confirmed", status.confirmed());
        // 确认后的身份字段只在 confirmed 时下发（避免中间态泄漏）
        if (status.confirmed()) {
            data.put("botId", status.botId());
            data.put("userId", status.userId());
        }
        return Result.ok(data);
    }
}