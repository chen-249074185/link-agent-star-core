package com.star.agent.user;

import com.star.agent.infra.web.Result;
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
@RestController
@RequestMapping("/api/user")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/send-code")
    public Result<Void> sendCode(@RequestBody Map<String, String> body) {
        authService.sendCode(body.get("email"));
        return Result.ok(null);
    }

    @PostMapping("/register")
    public Result<AuthResult> register(@RequestBody AuthRequest req) {
        return Result.ok(authService.register(req.type(), req.username(), req.password(), req.email(), req.code()));
    }

    @PostMapping("/login")
    public Result<AuthResult> login(@RequestBody AuthRequest req) {
        return Result.ok(authService.login(req.type(), req.username(), req.password(), req.email(), req.code()));
    }
}