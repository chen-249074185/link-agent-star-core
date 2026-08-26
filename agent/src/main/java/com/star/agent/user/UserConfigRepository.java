package com.star.agent.user;

import com.star.agent.user.dao.UserConfigDao;
import com.star.agent.user.domain.UserConfigDo;
import org.springframework.stereotype.Component;

// 用户配置仓库：user_config 表读写（当前存猎聘 Token，按 userId 隔离——每用户自己的猎聘账号）
@Component
public class UserConfigRepository {

    private final UserConfigDao dao;

    public UserConfigRepository(UserConfigDao dao) {
        this.dao = dao;
    }

    // 保存某用户的猎聘 Token（只写该用户自己的行，别人覆盖不了）
    public void saveLiepinToken(String userId, String token) {
        UserConfigDo c = new UserConfigDo();
        c.setUserId(userId);
        c.setLiepinToken(token);
        dao.upsert(c);
    }

    // 取某用户的猎聘 Token；未配置 → null（调用方引导配置，绝不回退用别人的）
    public String findLiepinToken(String userId) {
        UserConfigDo c = dao.findByUserId(userId);
        return c == null ? null : c.getLiepinToken();
    }
}