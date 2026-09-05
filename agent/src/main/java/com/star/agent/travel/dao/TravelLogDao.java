package com.star.agent.travel.dao;

import com.star.agent.travel.domain.TravelLogDo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

// 游记 Mapper：travel_log 表（SQL 在 resources/mapper/TravelLogDao.xml）
@Mapper
public interface TravelLogDao {

    // 发布游记
    int insert(TravelLogDo log);

    // 查某用户全部游记（时间倒序，足迹地图/游记列表用）
    List<TravelLogDo> findByUserId(String userId);

    // 按 id 查某用户的游记（无/不属于本人 → null）
    TravelLogDo findByIdAndUserId(Long id, String userId);

    // 查某用户某省有几篇游记（删除时判断省份是否熄灭）
    int countByUserIdAndProvince(String userId, String province);

    // 删某用户某篇游记
    int deleteByIdAndUserId(Long id, String userId);

    // 查某用户去过的省份（点亮地图预计算用）
    List<String> findDistinctProvinces(String userId);

    // 删某用户全部游记（测试清理用）
    int deleteByUserId(String userId);
}