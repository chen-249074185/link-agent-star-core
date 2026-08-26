package com.star.agent.user;

import com.star.agent.user.dao.UserConfigDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// 用户配置仓库集成测试（真实 MySQL user_config 表）：
// 每用户 Token 隔离（u1 存的不影响 u2）、覆盖更新、未配置 null
@SpringBootTest(properties = "linkagent.wechat.bot-enabled=false")
class UserConfigRepositoryTest {

    @Autowired
    UserConfigRepository repository;

    @Autowired
    UserConfigDao dao;

    @AfterEach
    void tearDown() {
        dao.deleteByUserId("itest:cfg1");
        dao.deleteByUserId("itest:cfg2");
    }

    @Test
    void saveAndFind_roundTrip() {
        repository.saveLiepinToken("itest:cfg1", "token-A");

        assertEquals("token-A", repository.findLiepinToken("itest:cfg1"));
    }

    // 每用户隔离：u1 配了自己的，u2 查不到（绝不串号）
    @Test
    void userIsolation_otherUserCannotRead() {
        repository.saveLiepinToken("itest:cfg1", "token-A");

        assertNull(repository.findLiepinToken("itest:cfg2"), "未配置的用户查不到别人 Token");
    }

    @Test
    void saveAgain_overwrites() {
        repository.saveLiepinToken("itest:cfg1", "old");
        repository.saveLiepinToken("itest:cfg1", "new");

        assertEquals("new", repository.findLiepinToken("itest:cfg1"));
    }

    @Test
    void noConfig_returnsNull() {
        assertNull(repository.findLiepinToken("no-such-user"));
    }
}