package com.star.agent.user;

import com.star.agent.adapter.wechat.BotManager;
import com.star.agent.infra.web.BizException;
import com.star.agent.user.domain.UserDo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

// 认证服务：注册/登录两路径 + 绑定/换绑邮箱 + 账号合并（契约 10-25 冻结）
//   注册：username 路径（用户名+密码）/ email 路径（邮箱+验证码，免密码）
//   登录：username 路径 / email 路径（未注册邮箱输对码 = 登录即注册）
//   绑定：邮箱被纯邮箱账号占用 → 两段式确认（bind 返回 mergeToken → confirm-merge 执行合并）
//   微信：bindWechat/unbindWechat（10-31，扫码绑定实现三端互通）
// 全部绑定 userId 维度，JWT subject=userId
@Component
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern USERNAME = Pattern.compile("^[\\w\\u4e00-\\u9fa5]{3,20}$");
    private static final String MERGE_KEY = "linkagent:auth:merge:";
    private static final Duration MERGE_TTL = Duration.ofMinutes(5);

    private final UserRepository userRepository;
    private final VerifyCodeService verifyCodeService;
    private final JwtService jwtService;
    private final StringRedisTemplate redis;
    private final MergeService mergeService;
    private final WechatScanService wechatScanService;   // 微信扫码：二维码/状态/确认（10-31 绑定用）
    private final BotManager botManager;                 // 扫码确认后把 Bot 拉上线（10-31）
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository, VerifyCodeService verifyCodeService, JwtService jwtService,
                       StringRedisTemplate redis, MergeService mergeService, WechatScanService wechatScanService,
                       BotManager botManager) {
        this.userRepository = userRepository;
        this.verifyCodeService = verifyCodeService;
        this.jwtService = jwtService;
        this.redis = redis;
        this.mergeService = mergeService;
        this.wechatScanService = wechatScanService;
        this.botManager = botManager;
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

    // 绑定/换绑邮箱（JWT 鉴权后 userId 传入；换绑=旧邮箱解绑+新邮箱绑定，被占用同样走合并）：
    //   邮箱无人用 → 直接绑；被纯邮箱账号占用 → 返回 mergeToken 待确认；被有身份账号占用 → 拒绝
    public BindResult bindEmail(String userId, String email, String code) {
        checkEmail(email);
        verifyCodeService.verify(email, code);
        UserDo me = userRepository.findByUserId(userId);
        if (me == null) {
            throw new BizException(401, "账号不存在，请重新登录");
        }
        if (email.equals(me.getEmail())) {
            return new BindResult(true, false, null);   // 本来就是这个邮箱
        }
        UserDo owner = userRepository.findByEmail(email);
        if (owner == null) {
            userRepository.updateEmail(userId, email);
            return new BindResult(true, false, null);
        }
        if (owner.getUserId().equals(userId)) {
            return new BindResult(true, false, null);
        }
        boolean pureEmailAccount = owner.getUsername() == null && owner.getPasswordHash() == null;
        if (!pureEmailAccount) {
            throw new BizException(409, "该邮箱已绑定其他账号，无法绑定");
        }
        // 纯邮箱账号占用 → 待确认合并（mergeToken 存 Redis，5 分钟有效）
        String mergeToken = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        redis.opsForValue().set(MERGE_KEY + mergeToken,
                userId + "|" + owner.getUserId(), MERGE_TTL);
        return new BindResult(false, true, mergeToken);
    }

    // 二次确认合并：token 属于当前用户才执行——纯邮箱账号 B 的全部数据并入 A，然后邮箱绑到 A
    public BindResult confirmMerge(String userId, String mergeToken) {
        if (mergeToken == null || mergeToken.isBlank()) {
            throw new BizException(400, "缺少合并确认凭证");
        }
        String raw = redis.opsForValue().get(MERGE_KEY + mergeToken);
        if (raw == null) {
            throw new BizException(400, "合并确认已过期，请重新绑定");
        }
        String[] parts = raw.split("\\|");
        if (parts.length != 2 || !parts[0].equals(userId)) {
            throw new BizException(400, "合并确认无效");
        }
        String fromUserId = parts[1];
        UserDo fromUser = userRepository.findByUserId(fromUserId);
        if (fromUser == null || fromUser.getEmail() == null) {
            throw new BizException(400, "被并入账号不存在");
        }
        String email = fromUser.getEmail();
        mergeService.merge(fromUserId, userId);          // 数据迁移 + 删被并入账号（事务，失败回滚不删）
        userRepository.updateEmail(userId, email);       // 邮箱绑定到保留方
        redis.delete(MERGE_KEY + mergeToken);
        return new BindResult(true, true, null);
    }

    // 个人资料（个人信息页展示用）：邮箱脱敏（a***@qq.com），不泄露明文
    public Map<String, Object> profile(String userId) {
        UserDo me = userRepository.findByUserId(userId);
        if (me == null) {
            throw new BizException(401, "账号不存在，请重新登录");
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("userId", me.getUserId());
        data.put("username", me.getUsername());
        data.put("email", me.getEmail() == null ? null : maskEmail(me.getEmail()));
        data.put("emailBound", me.getEmail() != null);
        data.put("wechatId", me.getWechatId() == null ? null : maskWechat(me.getWechatId()));
        data.put("wechatBound", me.getWechatId() != null);
        return data;
    }

    // 累计 token 用量（对话页顶栏展示；估算值，量级参考）
    public long tokenUsage(String userId) {
        return userRepository.findTotalTokens(userId);
    }

    // 绑定微信（登录页/个人信息页扫码确认后调用，JWT 鉴权）：
    //   ticket 必须已确认（CONFIRMED）→ 把"机器人宿主微信"身份写入账号 wechat_id
    //   → 微信侧旧数据并入账号（复用账号合并 MergeService：对话/摘要/知识库/猎聘 Token/Redis 记忆全迁）
    //   → 微信消息入口查表映射后，微信对话与网页共用同一 userId（三端互通）
    public Map<String, Object> bindWechat(String userId, String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new BizException(400, "缺少扫码凭证");
        }
        WechatScanService.ScanStatus status = wechatScanService.status(ticket);
        if (status == null) {
            throw new BizException(400, "扫码会话不存在或已过期，请重新扫码");
        }
        if (!status.confirmed() || status.userId() == null || status.userId().isBlank()) {
            throw new BizException(400, "尚未扫码确认，请先扫码");
        }
        String wechatId = status.userId();   // 扫码确认后拿到的宿主微信身份
        UserDo me = userRepository.findByUserId(userId);
        if (me == null) {
            throw new BizException(401, "账号不存在，请重新登录");
        }
        if (wechatId.equals(me.getWechatId())) {
            wechatScanService.consume(ticket);
            return Map.of("wechatBound", true, "already", true);
        }
        UserDo owner = userRepository.findByWechatId(wechatId);
        if (owner != null && !owner.getUserId().equals(userId)) {
            throw new BizException(409, "该微信已绑定其他账号，无法绑定");
        }
        userRepository.bindWechat(userId, wechatId);
        wechatScanService.consume(ticket);
        // 微信侧旧数据并入账号（微信身份在 users 表无行，merge 最后一步删了个寂寞，无害）——
        // 之后微信对话经映射走账号身份，历史/知识库/记忆全部互通
        mergeService.merge(wechatId, userId);
        // 扫码确认后把 Bot 拉上线（否则手机侧 bot 不可用）：用确认返回的登录上下文保存会话并启动
        if (botManager != null) {
            botManager.startFromScan(status.botToken(), wechatId, status.botId(), status.baseUrl());
        }
        logger.info("用户 {} 绑定微信 {}", userId, maskWechat(wechatId));
        return Map.of("wechatBound", true, "already", false);
    }

    // 微信身份脱敏：o9cqxxxxxxxx@im.wechat → o9cq***@im.wechat
    static String maskWechat(String wechatId) {
        int at = wechatId.indexOf('@');
        if (at <= 0) {
            return wechatId;
        }
        String name = wechatId.substring(0, at);
        String tail = name.length() > 4 ? name.substring(0, 4) : name;
        return tail + "***" + wechatId.substring(at);
    }

    // 微信扫码免密登录（10-31 用户拍板）：扫码确认 = 微信官方验证了身份（微信即凭据）
// 已绑定的微信 → 直接签发 JWT 进入（不用再输账号密码）；未绑定 → 返回 bound=false 走登录/注册表单绑定
public AuthResult scanLogin(String ticket) {
    if (ticket == null || ticket.isBlank()) {
        throw new BizException(400, "缺少扫码凭证");
    }
    WechatScanService.ScanStatus status = wechatScanService.status(ticket);
    if (status == null) {
        throw new BizException(400, "扫码会话不存在或已过期，请重新扫码");
    }
    if (!status.confirmed() || status.userId() == null || status.userId().isBlank()) {
        throw new BizException(400, "尚未扫码确认，请先扫码");
    }
    UserDo bound = userRepository.findByWechatId(status.userId());
    if (bound == null) {
        return null;   // 未绑定：前端转登录/注册表单
    }
    wechatScanService.consume(ticket);   // 免密登录成功即消费（一次性）
    // 已绑定微信再扫码 = 换宿主/重登：同样把 Bot 拉上线（同 botId 顶号重启）
    if (botManager != null) {
        botManager.startFromScan(status.botToken(), status.userId(), status.botId(), status.baseUrl());
    }
    return new AuthResult(jwtService.issue(bound.getUserId()), bound.getUserId());
}

// 解绑微信（个人信息页"解绑"按钮）：wechat_id 置空，微信身份恢复独立
    // 已并入账号的历史数据保留（不分离）；解绑后微信新对话回到微信身份侧
    public void unbindWechat(String userId) {
        UserDo me = userRepository.findByUserId(userId);
        if (me == null) {
            throw new BizException(401, "账号不存在，请重新登录");
        }
        if (me.getWechatId() == null) {
            return;   // 本来就没绑
        }
        userRepository.bindWechat(userId, null);   // 复用同一 SQL：传 null = SET wechat_id = NULL
        logger.info("用户 {} 解绑微信 {}", userId, maskWechat(me.getWechatId()));
    }

    // 邮箱脱敏：a***@qq.com（首字母保留 + 星号 + 域名）
    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return email;
        }
        return email.charAt(0) + "***" + email.substring(at);
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