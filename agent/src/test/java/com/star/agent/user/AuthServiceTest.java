package com.star.agent.user;

import com.star.agent.infra.web.BizException;
import com.star.agent.user.domain.UserDo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 认证服务单测（mock 仓库/验证码/JWT，真实 BCrypt）：
// 注册两路径、登录两路径、登录即注册、校验规则、统一话术
class AuthServiceTest {

    private final UserRepository repo = mock(UserRepository.class);
    private final VerifyCodeService verifyCodeService = mock(VerifyCodeService.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final MergeService mergeService = mock(MergeService.class);
    private final AuthService service = new AuthService(repo, verifyCodeService, jwtService, redis, mergeService);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        when(jwtService.issue(anyString())).thenReturn("token-x");
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    // ---------- 注册：username 路径 ----------

    @Test
    void registerUsername_success_hashesPassword() {
        service.register("username", "小明同学", "123456", null, null);

        ArgumentCaptor<UserDo> captor = ArgumentCaptor.forClass(UserDo.class);
        verify(repo).insert(captor.capture());
        UserDo u = captor.getValue();
        assertEquals("小明同学", u.getUsername());
        assertNotNull(u.getUserId());
        assertTrue(u.getUserId().startsWith("u"), "userId 应为系统生成");
        assertFalse(u.getPasswordHash().contains("123456"), "密码必须哈希存储");
        assertTrue(encoder.matches("123456", u.getPasswordHash()), "哈希应能通过 BCrypt 匹配");
        assertNull(u.getEmail());
    }

    @Test
    void registerUsername_taken_conflict() {
        when(repo.findByUsername("小明同学")).thenReturn(new UserDo());

        BizException e = assertThrows(BizException.class,
                () -> service.register("username", "小明同学", "123456", null, null));
        assertEquals(409, e.getCode());
        verify(repo, never()).insert(any());
    }

    @Test
    void registerUsername_badUsername_rejected() {
        BizException e = assertThrows(BizException.class,
                () -> service.register("username", "ab", "123456", null, null));
        assertEquals(400, e.getCode(), "2 位用户名应被拒");
        verify(repo, never()).insert(any());
    }

    @Test
    void registerUsername_shortPassword_rejected() {
        BizException e = assertThrows(BizException.class,
                () -> service.register("username", "小明同学", "123", null, null));
        assertEquals(400, e.getCode());
        verify(repo, never()).insert(any());
    }

    // ---------- 注册：email 路径（成功即自动登录） ----------

    @Test
    void registerEmail_success_autoLogin() {
        when(repo.findByEmail("a@qq.com")).thenReturn(null);

        AuthResult result = service.register("email", null, null, "a@qq.com", "123456");

        verify(verifyCodeService).verify("a@qq.com", "123456");
        ArgumentCaptor<UserDo> captor = ArgumentCaptor.forClass(UserDo.class);
        verify(repo).insert(captor.capture());
        assertEquals("a@qq.com", captor.getValue().getEmail());
        assertEquals("token-x", result.token());
        assertEquals(captor.getValue().getUserId(), result.userId());
    }

    @Test
    void registerEmail_taken_conflict() {
        when(repo.findByEmail("a@qq.com")).thenReturn(new UserDo());

        BizException e = assertThrows(BizException.class,
                () -> service.register("email", null, null, "a@qq.com", "123456"));
        assertEquals(409, e.getCode());
    }

    // ---------- 登录：username 路径 ----------

    @Test
    void loginUsername_success() {
        UserDo u = new UserDo();
        u.setUserId("u1");
        u.setUsername("小明同学");
        u.setPasswordHash(encoder.encode("123456"));
        when(repo.findByUsername("小明同学")).thenReturn(u);

        AuthResult result = service.login("username", "小明同学", "123456", null, null);

        assertEquals("u1", result.userId());
        assertEquals("token-x", result.token());
    }

    @Test
    void loginUsername_wrongPassword_unifiedMessage() {
        UserDo u = new UserDo();
        u.setPasswordHash(encoder.encode("123456"));
        when(repo.findByUsername("小明同学")).thenReturn(u);

        BizException e = assertThrows(BizException.class,
                () -> service.login("username", "小明同学", "wrong", null, null));
        assertEquals(401, e.getCode());
    }

    @Test
    void loginUsername_unknownUser_unifiedMessage() {
        when(repo.findByUsername("小明同学")).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.login("username", "小明同学", "123456", null, null));
        assertEquals(401, e.getCode());
    }

    // ---------- 登录：email 路径（未注册=登录即注册） ----------

    @Test
    void loginEmail_knownUser_token() {
        UserDo u = new UserDo();
        u.setUserId("u1");
        u.setEmail("a@qq.com");
        when(repo.findByEmail("a@qq.com")).thenReturn(u);

        AuthResult result = service.login("email", null, null, "a@qq.com", "123456");

        verify(verifyCodeService).verify("a@qq.com", "123456");
        assertEquals("u1", result.userId());
        verify(repo, never()).insert(any());
    }

    @Test
    void loginEmail_unknownEmail_autoRegister() {
        when(repo.findByEmail("a@qq.com")).thenReturn(null);

        AuthResult result = service.login("email", null, null, "a@qq.com", "123456");

        ArgumentCaptor<UserDo> captor = ArgumentCaptor.forClass(UserDo.class);
        verify(repo).insert(captor.capture());
        assertEquals("a@qq.com", captor.getValue().getEmail());
        assertNull(captor.getValue().getUsername(), "登录即注册的账号无用户名（纯邮箱账号，可被合并）");
        assertEquals("token-x", result.token());
    }

    // ---------- 边界 ----------

    @Test
    void badType_rejected() {
        assertThrows(BizException.class, () -> service.register("phone", null, null, null, null));
        assertThrows(BizException.class, () -> service.login("phone", null, null, null, null));
    }

    @Test
    void badEmail_rejected() {
        BizException e = assertThrows(BizException.class, () -> service.sendCode("not-an-email"));
        assertEquals(400, e.getCode());
        org.mockito.Mockito.verify(verifyCodeService, never()).send(anyString());
    }

    // ---------- 绑定/换绑邮箱 + 账号合并 ----------

    @Test
    void bindEmail_freeEmail_bound() {
        UserDo me = new UserDo();
        me.setUserId("u1");
        when(repo.findByUserId("u1")).thenReturn(me);
        when(repo.findByEmail("a@qq.com")).thenReturn(null);

        BindResult r = service.bindEmail("u1", "a@qq.com", "123456");

        assertTrue(r.bound());
        assertFalse(r.merged());
        assertNull(r.mergeToken());
        verify(repo).updateEmail("u1", "a@qq.com");
        verify(verifyCodeService).verify("a@qq.com", "123456");
    }

    @Test
    void bindEmail_sameEmail_alreadyBound() {
        UserDo me = new UserDo();
        me.setUserId("u1");
        me.setEmail("a@qq.com");
        when(repo.findByUserId("u1")).thenReturn(me);

        BindResult r = service.bindEmail("u1", "a@qq.com", "123456");

        assertTrue(r.bound());
        verify(repo, never()).updateEmail(anyString(), anyString());
    }

    @Test
    void bindEmail_occupiedByIdentifiedAccount_rejected() {
        UserDo me = new UserDo();
        me.setUserId("u1");
        when(repo.findByUserId("u1")).thenReturn(me);
        UserDo owner = new UserDo();
        owner.setUserId("u2");
        owner.setUsername("小明同学");
        owner.setPasswordHash("hash");
        when(repo.findByEmail("a@qq.com")).thenReturn(owner);

        BizException e = assertThrows(BizException.class, () -> service.bindEmail("u1", "a@qq.com", "123456"));
        assertEquals(409, e.getCode(), "有用户名/密码的账号不能并入");
        verify(mergeService, never()).merge(anyString(), anyString());
    }

    @Test
    void bindEmail_occupiedByPureEmailAccount_pendingMerge() {
        UserDo me = new UserDo();
        me.setUserId("u1");
        when(repo.findByUserId("u1")).thenReturn(me);
        UserDo owner = new UserDo();
        owner.setUserId("u2");
        owner.setEmail("a@qq.com");   // 纯邮箱账号：无用户名无密码
        when(repo.findByEmail("a@qq.com")).thenReturn(owner);

        BindResult r = service.bindEmail("u1", "a@qq.com", "123456");

        assertFalse(r.bound(), "待确认合并，未直接绑定");
        assertTrue(r.merged(), "语义=触发合并流程");
        assertNotNull(r.mergeToken());
        verify(valueOps).set(org.mockito.ArgumentMatchers.startsWith("linkagent:auth:merge:"),
                org.mockito.ArgumentMatchers.eq("u1|u2"), org.mockito.ArgumentMatchers.any());
        verify(repo, never()).updateEmail(anyString(), anyString());
        verify(mergeService, never()).merge(anyString(), anyString());
    }

    @Test
    void confirmMerge_validToken_mergesAndBinds() {
        when(valueOps.get("linkagent:auth:merge:tk1234567890abcd")).thenReturn("u1|u2");
        UserDo from = new UserDo();
        from.setUserId("u2");
        from.setEmail("a@qq.com");
        when(repo.findByUserId("u2")).thenReturn(from);

        BindResult r = service.confirmMerge("u1", "tk1234567890abcd");

        assertTrue(r.bound());
        assertTrue(r.merged());
        verify(mergeService).merge("u2", "u1");
        verify(repo).updateEmail("u1", "a@qq.com");
        verify(redis).delete("linkagent:auth:merge:tk1234567890abcd");
    }

    @Test
    void confirmMerge_expiredToken_rejected() {
        when(valueOps.get("linkagent:auth:merge:gone")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.confirmMerge("u1", "gone"));
        assertEquals(400, e.getCode());
        verify(mergeService, never()).merge(anyString(), anyString());
    }

    @Test
    void confirmMerge_tokenNotForThisUser_rejected() {
        when(valueOps.get("linkagent:auth:merge:tk1234567890abcd")).thenReturn("u9|u2");

        BizException e = assertThrows(BizException.class, () -> service.confirmMerge("u1", "tk1234567890abcd"));
        assertEquals(400, e.getCode());
        verify(mergeService, never()).merge(anyString(), anyString());
    }
}