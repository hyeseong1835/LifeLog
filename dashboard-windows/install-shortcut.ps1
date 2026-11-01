# 시작 메뉴(모든 앱)에 "LifeLog 대시보드" 바로가기를 만든다. 타일 고정은 시작 메뉴에서 우클릭 → 시작 화면에 고정.
#   powershell -ExecutionPolicy Bypass -File dashboard-windows/install-shortcut.ps1
#   옵션: -Desktop (바탕 화면에도)  -Remove (지움)
param([switch]$Desktop, [switch]$Remove)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $root "dashboard-windows\src\LifeLog.Dashboard.Windows\bin\Debug\net10.0-windows10.0.19041.0\lifelog-dashboard.exe"
if (-not (Test-Path $exe)) { throw "대시보드가 빌드되어 있지 않습니다: $exe (dotnet build dashboard-windows)" }
$targets = @((Join-Path ([Environment]::GetFolderPath("Programs")) "LifeLog 대시보드.lnk"))
if ($Desktop) { $targets += (Join-Path ([Environment]::GetFolderPath("Desktop")) "LifeLog 대시보드.lnk") }
foreach ($lnk in $targets) {
    if ($Remove) { if (Test-Path $lnk) { Remove-Item $lnk; Write-Host "지움: $lnk" }; continue }
    $sh = New-Object -ComObject WScript.Shell
    $s = $sh.CreateShortcut($lnk)
    $s.TargetPath = $exe
    $s.WorkingDirectory = Split-Path $exe
    $s.IconLocation = "$exe,0"
    $s.Description = "LifeLog 대시보드"
    $s.Save()
    Write-Host "만듦: $lnk"
}
