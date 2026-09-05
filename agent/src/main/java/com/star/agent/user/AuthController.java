package com.star.agent.user;

import com.star.agent.infra.web.AuthContext;
import com.star.agent.infra.web.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// 用户认证接口（登录/注册页前端调用，契约 10-25 冻结，详见 docs/API.md）：
//   POST /api/user/send-code  {"email"}                          = 发验证码（60s 限流）
//   POST /api/user/register   {"type":"username",...} 或 {"type":"email",...}
//   POST /api/user/login      {"type":"username",...} 或 {"type":"email",...}
//     成功 data = {token, userId}（邮箱路径未注册=自动注册，登录即注册）
//   POST /api/user/bind-email {"email","code"}                    = 绑定/换绑邮箱（JWT 鉴权）
//     邮箱被纯邮箱账号占用 → data.mergeToken 待确认
//   POST /api/user/confirm-merge {"mergeToken"}                   = 二次确认执行账号合并（JWT 鉴权）
//   POST /api/user/bind-wechat {"ticket"}                         = 微信扫码绑定（JWT 鉴权，10-31）
//     前置：/api/wechat/scan/start + /status 轮询到 CONFIRMED；绑定后微信与网页同一 userId 互通
@Tag(name = "用户认证", description = "注册/登录/邮箱绑定/账号合并/微信绑定，契约冻结版（docs/API.md）")
@RestController
@RequestMapping("/api/user")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "发送邮箱验证码", description = "60s 限流；错 5 次锁 10 分钟")
    @PostMapping("/send-code")
    public Result<Void> sendCode(@RequestBody Map<String, String> body) {
        authService.sendCode(body.get("email"));
        return Result.ok(null);
    }

    @Operation(summary = "注册", description = "type=username（用户名+密码）或 type=email（邮箱+验证码）")
    @PostMapping("/register")
    public Result<AuthResult> register(@RequestBody AuthRequest req) {
        return Result.ok(authService.register(req.type(), req.username(), req.password(), req.email(), req.code()));
    }

    @Operation(summary = "登录", description = "返回 {token, userId}；邮箱路径未注册=自动注册（登录即注册）")
    @PostMapping("/login")
    public Result<AuthResult> login(@RequestBody AuthRequest req) {
        return Result.ok(authService.login(req.type(), req.username(), req.password(), req.email(), req.code()));
    }

    // 个人资料（个人信息页）：用户名 + 邮箱脱敏状态；userId 从 JWT 取
    @GetMapping("/me")
    public Result<Map<String, Object>> me(HttpServletRequest request) {
        String userId = AuthContext.userId(request);
        if (userId == null) {
            return Result.fail(401, "未登录或登录已过期");
        }
        return Result.ok(authService.profile(userId));
    }

    // 累计 token 用量（对话页顶栏展示）；userId 从 JWT 取
    @Operation(summary = "累计 token 用量", description = "估算值（4 字符≈1 token），对话埋点原子自增")
    @GetMapping("/tokens")
    public Result<Map<String, Object>> tokens(HttpServletRequest request) {
        String userId = AuthContext.userId(request);
        if (userId == null) {
            return Result.fail(401, "未登录或登录已过期");
        }
        return Result.ok(Map.of("totalTokens", authService.tokenUsage(userId)));
    }

    // 绑定/换绑邮箱：userId 从 JWT 取（AuthContext），不信任请求参数
    @PostMapping("/bind-email")
    public Result<BindResult> bindEmail(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userId = AuthContext.userId(request);
        if (userId == null) {
            return Result.fail(401, "未登录或登录已过期");
        }
        return Result.ok(authService.bindEmail(userId, body.get("email"), body.get("code")));
    }

    // 二次确认账号合并：userId 从 JWT 取，与 mergeToken 归属校验一致才执行
    @PostMapping("/confirm-merge")
    public Result<BindResult> confirmMerge(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userId = AuthContext.userId(request);
        if (userId == null) {
            return Result.fail(401, "未登录或登录已过期");
        }
        return Result.ok(authService.confirmMerge(userId, body.get("mergeToken")));
    }

    // 微信扫码绑定：ticket 必须已轮询到 CONFIRMED；userId 从 JWT 取
    @PostMapping("/bind-wechat")
    public Result<Map<String, Object>> bindWechat(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userId = AuthContext.userId(request);
        if (userId == null) {
            return Result.fail(401, "未登录或登录已过期");
        }
        return Result.ok(authService.bindWechat(userId, body.get("ticket")));
    }

    // 微信扫码免密登录（免 JWT，登录页未登录用）：已绑定 → 直接签发 JWT；未绑定 → data=null 走注册绑定
    @Operation(summary = "微信扫码免密登录", description = "已绑定微信扫码 → 免密签发 JWT；未绑定返回 null 转表单")
    @PostMapping("/scan-login")
    public Result<AuthResult> scanLogin(@RequestBody Map<String, String> body) {
        return Result.ok(authService.scanLogin(body.get("ticket")));
    }

    // 解绑微信（个人信息页"解绑"按钮）：微信身份恢复独立，已并入数据保留
    @PostMapping("/unbind-wechat")
    public Result<Void> unbindWechat(HttpServletRequest request) {
        String userId = AuthContext.userId(request);
        if (userId == null) {
            return Result.fail(401, "未登录或登录已过期");
        }
        authService.unbindWechat(userId);
        return Result.ok(null);
    }
}