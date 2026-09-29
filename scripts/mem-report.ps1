# 内存基线/对比测量脚本
# 统计 PPPoEDialer.exe 及其 WebView2 子进程(msedgewebview2.exe)的私有内存,
# 用于「代理 + 按需 UI」架构改造前后的内存对比。
#
# 用法:
#   powershell -ExecutionPolicy Bypass -File scripts\mem-report.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\mem-report.ps1 -WatchSeconds 30   # 每 5s 采样一次共 30s

param(
    [int]$WatchSeconds = 0
)

function Get-AppMemory {
    # PPPoEDialer 主进程(代理/UI 同一 exe)
    $main = Get-Process -Name "PPPoEDialer" -ErrorAction SilentlyContinue
    # WebView2 子进程(父进程为 PPPoEDialer 的 msedgewebview2)
    $webviews = Get-CimInstance Win32_Process -Filter "Name='msedgewebview2.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            $p = Get-Process -Id $_.ParentProcessId -ErrorAction SilentlyContinue
            $p -and $p.ProcessName -match 'PPPoEDialer|msedgewebview2'
        }
    $mainMB = 0.0
    if ($main) {
        foreach ($m in $main) { $mainMB += $m.PrivateMemorySize64 / 1MB }
    }
    $wvMB = 0.0
    $wvCount = 0
    foreach ($w in $webviews) {
        $proc = Get-Process -Id $w.ProcessId -ErrorAction SilentlyContinue
        if ($proc) {
            $wvMB += $proc.PrivateMemorySize64 / 1MB
            $wvCount++
        }
    }
    [pscustomobject]@{
        Time            = (Get-Date -Format 'HH:mm:ss')
        MainProcs       = @($main).Count
        MainMB          = [math]::Round($mainMB, 1)
        WebviewProcs    = $wvCount
        WebviewMB       = [math]::Round($wvMB, 1)
        TotalMB         = [math]::Round($mainMB + $wvMB, 1)
    }
}

$report = Get-AppMemory
$report | Format-Table -AutoSize

if ($WatchSeconds -gt 0) {
    $deadline = (Get-Date).AddSeconds($WatchSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        Get-AppMemory | Format-Table -AutoSize
    }
}
