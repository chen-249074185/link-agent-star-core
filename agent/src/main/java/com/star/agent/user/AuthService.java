package com.star.agent.user;

import com.star.agent.infra.web.BizException;
import com.star.agent.user.domain.UserDo;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

// 认证服务：注册/登录两路径（契约 10-25 冻结）
//   注册：username 路径（用户名+密码）/ email 路径（邮箱+验证码，免密码）
//   登录：username 路径 / email 路径（未注册邮箱输对码 = 登录即注册）
// 全部绑定 userId 维度，JWT subject=userId
@Component
public class AuthService {

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern USERNAME = Pattern.compile("^[\\w\\u4e00-\\u9fa5]{3,20}$");

    private final UserRepository userRepository;
    private final VerifyCodeService verifyCodeService;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository, VerifyCodeService verifyCodeService, JwtService jwtService) {
        this.userRepository = userRepository;
        this.verifyCodeService = verifyCodeService;
        this.jwtService = jwtService;
    }

    // 发送验证码（登录/注册共用）
    public void sendCode(String email) {
        checkEmail(email);
        verifyCodeService.send(email);
    }

    // 注册：按 type 路由（username/email 两路径）；邮箱路径成功即自动登录返回 token，用户名路径返回 null（前端跳登录页）
    public AuthResult register(String type, String username, String password, String email, String code) {
        if ("email".equals(type)) {
            return registerEmail(email, code);
        }
        if ("username".equals(type)) {
            registerUsername(username, password);
            return null;
        }
        throw new BizException(400, "type 必须为 username 或 email");
    }

    // 登录：按 type 路由（username/email 两路径）
    public AuthResult login(String type, String username, String password, String email, String code) {
        if ("email".equals(type)) {
            return loginEmail(email, code);
        }
        if ("username".equals(type)) {
            return loginUsername(username, password);
        }
        throw new BizException(400, "type 必须为 username 或 email");
    }

    private void registerUsername(String username, String password) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new BizException(400, "用户名需 3~20 位（字母/数字/下划线/中文）");
        }
        if (password == null || password.length() < 6) {
            throw new BizException(400, "密码至少 6 位");
        }
        if (userRepository.findByUsername(username) != null) {
            throw new BizException(409, "用户名已被占用");
        }
        UserDo u = new UserDo();
        u.setUserId(newUserId());
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(password));   // BCrypt 哈希，绝不明文
        userRepository.insert(u);
    }

    private AuthResult registerEmail(String email, String code) {
        checkEmail(email);
        verifyCodeService.verify(email, code);
        if (userRepository.findByEmail(email) != null) {
            throw new BizException(409, "该邮箱已注册，请直接登录");
        }
        UserDo u = new UserDo();
        u.setUserId(newUserId());
        u.setEmail(email);
        userRepository.insert(u);
        return new AuthResult(jwtService.issue(u.getUserId()), u.getUserId());   // 注册成功即自动登录
    }

    private AuthResult loginUsername(String username, String password) {
        UserDo u = userRepository.findByUsername(username);
        // 用户不存在/未设密码/密码错 → 同一话术（不泄露哪个错）
        if (u == null || u.getPasswordHash() == null || !encoder.matches(password, u.getPasswordHash())) {
            throw new BizException(401, "用户名或密码错误");
        }
        return new AuthResult(jwtService.issue(u.getUserId()), u.getUserId());
    }

    private AuthResult loginEmail(String email, String code) {
        checkEmail(email);
        verifyCodeService.verify(email, code);
        UserDo u = userRepository.findByEmail(email);
        if (u == null) {   // 登录即注册：未注册邮箱输对码自动建号（验证码=邮箱所有权证明）
            u = new UserDo();
            u.setUserId(newUserId());
            u.setEmail(email);
            userRepository.insert(u);
        }
        return new AuthResult(jwtService.issue(u.getUserId()), u.getUserId());
    }

    private static void checkEmail(String email) {
        if (email == null || !EMAIL.matcher(email).matches()) {
            throw new BizException(400, "邮箱格式不正确");
        }
    }

    private static String newUserId() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}