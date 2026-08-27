package com.star.agent.user.dao;

import com.star.agent.user.domain.UserDo;
import org.apache.ibatis.annotations.Mapper;

// 用户账号 DAO：users 表的存查（SQL 在 resources/mapper/UserDao.xml）
@Mapper
public interface UserDao {

    // 建账号（userId 主键唯一）
    int insert(UserDo user);

    // 按唯一身份查（无 → null）
    UserDo findByUserId(String userId);

    // 按用户名查（无 → null）
    UserDo findByUsername(String username);

    // 按邮箱查（无 → null；邮箱唯一约束保证最多一人）
    UserDo findByEmail(String email);

    // 绑定/换绑邮箱（账号合并用：先迁数据后改邮箱）
    int updateEmail(String userId, String email);

    // 删除账号（测试清理/注销用）
    int deleteByUserId(String userId);
}