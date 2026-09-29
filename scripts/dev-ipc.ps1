# Dev/verification helper for the two-process (agent + UI) architecture.
# Usage:
#   powershell -File scripts\dev-ipc.ps1 -Action agent -PipeName \\.\pipe\PPoEDialerDev
#   powershell -File scripts\dev-ipc.ps1 -Action ui    -PipeName \\.\pipe\PPoEDialerDev
#   powershell -File scripts\dev-ipc.ps1 -Action status -PipeName \\.\pipe\PPoEDialerDev
#   powershell -File scripts\dev-ipc.ps1 -Action mem
#   powershell -File scripts\dev-ipc.ps1 -Action exit  -PipeName \\.\pipe\PPoEDialerDev
# The PPPOEDIALER_PIPE env var redirects agent/UI to a test pipe so they
# never collide with a resident instance on the canonical name.
param(
    [Parameter(Mandatory = $true)][string]$Action,
    [string]$PipeName = "\\.\pipe\PPoEDialerDev"
)
$ErrorActionPreference = "Continue"
$exe = Join-Path $PSScriptRoot "..\build\bin\PPoEDialer.exe"

function Send-Ipc([string]$pipe, [string]$method) {
    $p = [System.IO.Pipes.NamedPipeClientStream]::new('.', $pipe.TrimStart('\\.\pipe\'),
        [System.IO.Pipes.PipeDirection]::InOut)
    $p.Connect(4000)
    $w = [System.IO.StreamWriter]::new($p)
    $w.NewLine = "`n"
    $w.WriteLine('{"type":"hello","version":1,"pid":-1}')
    $w.WriteLine('{"type":"req","id":1,"method":"' + $method + '","params":[]}')
    $w.Flush()
    $r = [System.IO.StreamReader]::new($p)
    while ($true) {
        $line = $r.ReadLine()
        if ($null -eq $line) { break }
        Write-Output ("reply: " + $line)
        if ($line -like '*"id":1*') { break }
    }
    $p.Dispose()
}

switch ($Action) {
    "agent" {
        $env:PPPOEDIALER_PIPE = $PipeName
        Start-Process -FilePath $exe -ArgumentList "--agent" | Out-Null
        Start-Sleep -Seconds 6
        & $MyInvocation.MyCommand.Path -Action status -PipeName $PipeName
    }
    "ui" {
        $env:PPPOEDIALER_PIPE = $PipeName
        Start-Process -FilePath $exe | Out-Null
        Start-Sleep -Seconds 8
        & $MyInvocation.MyCommand.Path -Action status -PipeName $PipeName
    }
    "status" {
        Get-Process PPoEDialer -ErrorAction SilentlyContinue |
            Select-Object Id, @{n = 'PrivateMB'; e = { [math]::Round($_.PrivateMemorySize64 / 1MB, 1) }} |
            Format-Table -AutoSize
        $wv = (Get-Process msedgewebview2 -ErrorAction SilentlyContinue | Measure-Object).Count
        Write-Output ("webview processes (system-wide): " + $wv)
    }
    "mem" {
        Get-Process PPoEDialer -ErrorAction SilentlyContinue | ForEach-Object {
            Write-Output ("PID " + $_.Id + "  WorkingSet=" + [math]::Round($_.WorkingSet64 / 1MB, 1) +
                "MB  PrivateBytes=" + [math]::Round($_.PrivateMemorySize64 / 1MB, 1) + "MB")
        }
    }
    "exit" {
        Send-Ipc $PipeName "ExitProgram"
        Start-Sleep -Seconds 7
        & $MyInvocation.MyCommand.Path -Action status -PipeName $PipeName
    }
    default { Write-Output "unknown action: $Action" }
}
