package com.star.agent.memory;

import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.List;

// 每日摘要 Mapper：daily_summary 表（SQL 在 resources/mapper/DailySummaryDao.xml）
@Mapper
public interface DailySummaryDao {

    // 存一条摘要（UNIQUE user_id+summary_date：同一天重复插会被数据库拒）
    int insert(DailySummaryDo summary);

    // 查某用户最近的摘要，倒序（最新的在前），limit 条
    List<DailySummaryDo> findByUserId(String userId, int limit);

    // 查某用户最后一条摘要的日期（没有返回 null）——判断"压缩缺口"用
    LocalDate findLatestDate(String userId);

    // 删某用户全部摘要（测试清理用）
    int deleteByUserId(String userId);
}