# 72 小时稳定性测试 + M4 预验收指南

> 目标：证明应用长期运行稳定（无崩溃/无内存泄漏/无串音），为 M4 验收与答辩提供实测数据。
> 判定标准：72h 运行堆使用不持续增长触顶、零崩溃、多 Bot/记忆隔离正常。

## 一、启动（挂机）

```powershell
# agent\agent 目录
.\scripts\start-monitor.ps1     # 后台启动 java -jar（带 GC 日志），PID 记录在 logs\app.pid
.\scripts\snapshot-heap.ps1     # 采一次启动基线
```

- GC 日志：`logs\gc.log`（滚动 5×10MB）
- 堆采样：`logs\heap-samples.log`

## 二、日常采样

```powershell
.\scripts\snapshot-heap.ps1                    # 手动采一次
.\scripts\monitor-heap.ps1 -IntervalHours 1    # 每小时自动采（另开一个窗口挂着）
```

## 三、M4 验收检查清单（10-25 检查）

| 检查项 | 判定标准 | 证据来源 |
|---|---|---|
| 运行时长 | ≥72h 无手动重启 | 进程 StartTime / app.pid |
| 无崩溃 | 全程无异常退出 | 进程仍在 + 日志无致命错误 |
| 内存无泄漏 | 堆使用平稳或 GC 后回落，不持续逼近 Xmx 上限（512m） | heap-samples.log 曲线 + gc.log |
| GC 正常 | 无频繁 Full GC（老年代不持续增长） | gc.log |
| 微信收发正常 | 挂机期间任意时刻发消息正常秒回 | 微信实测 |
| 免扫码恢复 | 挂机中断后重启仍免扫码 | 启动日志"会话恢复成功" |
| 多 Bot 不串音 | 两个 Bot 账号各自对话记忆独立 | 双账号实测 |
| 记忆不膨胀 | conversation 表无异常暴涨 | MySQL 行数 |
| 猎聘 Token 隔离 | 两个用户各自配置后查询互不干扰 | 双账号实测 |

## 四、判定与收尾

- 全部达标 → M4 通过：`heap-samples.log` 首尾采样对比 + `gc.log` 统计（Full GC 次数）写进验收记录
- 发现堆持续增长/频繁 Full GC → 截 heap 曲线 + jcmd `GC.class_histogram` 定位疑似泄漏类 → 记风险册排期修
- 数据保留：`logs/` 目录留档（gitignore），答辩展示用

## 五、注意事项

- 挂机期间不要跑 `mvn clean`（不影响运行中的 jar，无碍）
- 微信 Bot 会在启动时自动恢复/扫码；若 bot 掉线，日志会出现"需要扫码登录"（重新扫即可）
- 猎聘 Token 等敏感信息不进日志（Token 只经子进程环境变量）