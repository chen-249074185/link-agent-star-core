# 72h 稳定性测试启动脚本：java -jar 带 GC 日志（监控内存用）+ 后台启动 + 记录 PID
# 用法：在 agent\agent 目录执行  .\scripts\start-monitor.ps1
# 停止：Get-Process java | Stop-Process  （或记录 PID 后 Stop-Process -Id <pid>）
$jar = "target\agent-0.0.1-SNAPSHOT.jar"
$gcLog = "logs\gc.log"
New-Item -ItemType Directory -Force -Path "logs" | Out-Null

# GC 日志（JDK 21 统一日志）：时间戳+耗时+级别，滚动 5 个文件每个 10MB
$gcArgs = @(
    "-Xlog:gc:file=$gcLog:time,uptime,level,tags:filecount=5,filesize=10m",
    "-Xmx512m"   # 固定堆上限，便于观察是否触顶（泄漏会持续逼近上限）
)

$proc = Start-Process -FilePath "java" -ArgumentList ($gcArgs + @("-jar", $jar)) `
    -WindowStyle Hidden -PassThru
$proc.Id | Out-File "logs\app.pid"
Write-Output "应用已后台启动，PID=$($proc.Id)（已写入 logs\app.pid）"
Write-Output "GC 日志: logs\gc.log"
Write-Output "启动基线: .\scripts\snapshot-heap.ps1"