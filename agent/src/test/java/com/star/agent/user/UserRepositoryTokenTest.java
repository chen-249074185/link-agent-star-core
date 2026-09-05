package com.star.agent.user;

import com.star.agent.user.dao.UserDao;
import com.star.agent.user.domain.UserDo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

// 用户累计 token 用量仓库集成测试（真实 MySQL users.total_tokens 列）：
// 原子自增累加 / 每用户隔离 / 无账号返回 0 / 空参安全
@SpringBootTest(properties = "linkagent.wechat.bot-enabled=false")
class UserRepositoryTokenTest {

    private static final String U1 = "itest:tok1";
    private static final String U2 = "itest:tok2";

    @Autowired
    UserRepository repository;

    @Autowired
    UserDao dao;

    @BeforeEach
    void setUp() {
        UserDo u1 = new UserDo();
        u1.setUserId(U1);
        u1.setUsername("tok1");
        dao.insert(u1);
        UserDo u2 = new UserDo();
        u2.setUserId(U2);
        u2.setUsername("tok2");
        dao.insert(u2);
    }

    @AfterEach
    void tearDown() {
        dao.deleteByUserId(U1);
        dao.deleteByUserId(U2);
    }

    // 原子自增：多次调用按 delta 累加（并发安全靠 SQL 端自增）
    @Test
    void addTokens_accumulates() {
        assertEquals(0, repository.findTotalTokens(U1));
        repository.addTokens(U1, 100);
        repository.addTokens(U1, 50);
        repository.addTokens(U1, 25);
        assertEquals(175, repository.findTotalTokens(U1));
    }

    // 每用户隔离：u1 的累计不影响 u2
    @Test
    void userIsolation_otherUserUnaffected() {
        repository.addTokens(U1, 100);
        assertEquals(0, repository.findTotalTokens(U2));
    }

    // 无账号返回 0（不存在也不抛）
    @Test
    void missingUser_returnsZero() {
        assertEquals(0, repository.findTotalTokens("itest:tok-nobody"));
    }

    // 空参安全：userId null / delta<=0 直接忽略不落库
    @Test
    void addTokens_nullOrNonPositive_ignored() {
        repository.addTokens(null, 100);
        repository.addTokens(U1, 0);
        repository.addTokens(U1, -5);
        assertEquals(0, repository.findTotalTokens(U1));
    }
}