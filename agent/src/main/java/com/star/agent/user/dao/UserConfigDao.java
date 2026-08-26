package com.star.agent.user.dao;

import com.star.agent.user.domain.UserConfigDo;
import org.apache.ibatis.annotations.Mapper;

// 用户配置 DAO：user_config 表的存查（SQL 在 resources/mapper/UserConfigDao.xml）
@Mapper
public interface UserConfigDao {

    // 存/更新某用户的配置（user_id 主键冲突=覆盖）
    int upsert(UserConfigDo config);

    // 查某用户的配置（无 → null）
    UserConfigDo findByUserId(String userId);

    // 删某用户的配置（测试清理用）
    int deleteByUserId(String userId);
}