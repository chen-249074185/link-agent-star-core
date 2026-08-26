# 堆采样脚本：jcmd 定时抓堆快照，追加到 logs\heap-samples.log（72h 稳定性监控用）
# 用法：.scripts\snapshot-heap.ps1            = 采一次（启动基线/手动看）
#      .\scripts\monitor-heap.ps1 -IntervalHours 1   = 每小时采一次，直到手动停（Ctrl+C）
param([int]$IntervalHours = 0)

$pidFile = "logs\app.pid"
$outFile = "logs\heap-samples.log"
if (!(Test-Path $pidFile)) { Write-Error "找不到 logs\app.pid（先跑 .\scripts\start-monitor.ps1）"; exit 1 }
$appPid = (Get-Content $pidFile).Trim()
$jcmd = "D:\Java\jdk-21\bin\jcmd.exe"

function Sample {
    $stamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $heap = & $jcmd $appPid GC.heap_info 2>$null
    # 提取关键行（PSYoungGen/ParOldGen/PermGen 使用量），堆总量在总输出里
    $used = $heap | Select-String "used =" | Select-Object -Last 2 | ForEach-Object { $_.Line.Trim() } -ErrorAction SilentlyContinue
    $line = "$stamp | $($used -join ' ; ')"
    Add-Content -Path $outFile -Value $line
    Write-Output $line
}

Write-Output "采样应用 PID=$appPid，输出: $outFile"
if ($IntervalHours -le 0) {
    Sample
} else {
    Write-Output "每 $IntervalHours 小时采样一次（Ctrl+C 停止）..."
    while ($true) {
        Sample
        Start-Sleep -Seconds ($IntervalHours * 3600)
    }
}