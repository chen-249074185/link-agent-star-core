package com.star.agent.user;

import com.star.agent.user.dao.UserDao;
import com.star.agent.user.domain.UserDo;
import org.springframework.stereotype.Component;

// 用户账号仓库：users 表读写（W12 用户体系，注册/登录/查重共用）
@Component
public class UserRepository {

    private final UserDao dao;

    public UserRepository(UserDao dao) {
        this.dao = dao;
    }

    // 建账号
    public void insert(UserDo user) {
        dao.insert(user);
    }

    public UserDo findByUserId(String userId) {
        return dao.findByUserId(userId);
    }

    // 用户名查重（被占 → 非 null）
    public UserDo findByUsername(String username) {
        return dao.findByUsername(username);
    }

    // 邮箱查重（被占 → 非 null；账号合并/换绑时也要用它定位占用者）
    public UserDo findByEmail(String email) {
        return dao.findByEmail(email);
    }

    // 微信身份查绑定（被绑 → 非 null；扫码绑定前查占用）
    public UserDo findByWechatId(String wechatId) {
        return dao.findByWechatId(wechatId);
    }

    // 绑定微信：微信身份 ↔ 账号（扫码确认后调用）
    public void bindWechat(String userId, String wechatId) {
        dao.updateWechatId(userId, wechatId);
    }

    // 绑定/换绑邮箱
    public void updateEmail(String userId, String email) {
        dao.updateEmail(userId, email);
    }

    // 设置/覆盖人设（null = 清除恢复默认）
    public void savePersona(String userId, String persona) {
        dao.updatePersona(userId, persona);
    }

    // 当前人设文本（未设置返回 null）
    public String findPersona(String userId) {
        UserDo user = dao.findByUserId(userId);
        return user == null ? null : user.getPersona();
    }

    public void deleteByUserId(String userId) {
        dao.deleteByUserId(userId);
    }
}