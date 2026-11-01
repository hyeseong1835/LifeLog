# LifeLog 첫 실행: 관리자 접속으로 코어 스키마 적용 → 기존 데이터 이관 → 이 노트북을 기기로 등록.
# 비밀번호는 프롬프트에서 받고 어디에도 저장하지 않는다(환경 변수는 이 프로세스 안에서만 산다).
#
#   powershell -ExecutionPolicy Bypass -File tools/db/first-run.ps1 -ProjectRef ektnxxskcpngsiwmbftb
#   옵션: -Host aws-0-ap-northeast-1.pooler.supabase.com -Port 5432 -Database postgres -SkipImport -SkipSetup
param(
    [Parameter(Mandatory = $true)][string]$ProjectRef,
    [string]$DbHost = "aws-0-ap-northeast-1.pooler.supabase.com",
    [int]$Port = 5432,
    [string]$Database = "postgres",
    [string]$Device = "laptop",
    [switch]$SkipImport,
    [switch]$SkipSetup
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$user = "postgres.$ProjectRef"
$secure = Read-Host "Supabase DB 비밀번호 (postgres)" -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringUni([Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($secure))
# psycopg는 키워드 형식, Npgsql은 세미콜론 형식. 둘 다 URL이 아니라 특수 문자가 안전하다.
$env:LIFELOG_ADMIN_DSN = "host=$DbHost port=$Port dbname=$Database user=$user password=$plain sslmode=require"
$npgsql = "Host=$DbHost;Port=$Port;Database=$Database;Username=$user;Password=$plain;SSL Mode=Require"
$plain = $null

Write-Host "== 1. 코어 스키마 마이그레이션"
python tools/db/migrate.py
if ($LASTEXITCODE -ne 0) { throw "마이그레이션 실패" }

if (-not $SkipImport) {
    Write-Host "== 2. 기존 데이터 이관 (AW SQLite, 폰 TSV)"
    python tools/import/write.py --laptop $Device
    if ($LASTEXITCODE -ne 0) { throw "이관 실패" }
}

if (-not $SkipSetup) {
    Write-Host "== 3. 수집기 기기 등록"
    $exe = Join-Path $root "collector-windows\src\LifeLog.Collector.Windows\bin\Debug\net10.0-windows10.0.19041.0\lifelog-collector.exe"
    if (-not (Test-Path $exe)) { dotnet build collector-windows -nologo -v q | Out-Null }
    $env:LIFELOG_ADMIN_DSN = $npgsql
    # 이관이 기기를 먼저 만들었으면 비밀번호만 다시 받는다
    $cfgDir = Join-Path $env:LOCALAPPDATA "LifeLog\collector"
    & $exe setup --device $Device --name "노트북" --host $DbHost --port $Port --database $Database --tenant $ProjectRef
    if ($LASTEXITCODE -ne 0) {
        Write-Host "   setup 실패(이미 등록된 기기면 정상). reset_device_password로 비밀번호를 다시 받습니다."
        & $exe setup-reset --device $Device --host $DbHost --port $Port --database $Database --tenant $ProjectRef
        if ($LASTEXITCODE -ne 0) { throw "기기 설정 실패" }
    }
    foreach ($p in "win-window", "win-afk", "win-power", "win-media") {
        & $exe install "plugins/$p" --grant-all | Select-Object -Last 1 | ForEach-Object { $_.Substring(0, [Math]::Min(80, $_.Length)) }
    }
    & $exe status
}
$env:LIFELOG_ADMIN_DSN = $null
$npgsql = $null
Write-Host "== 완료. 수집 시작: $exe run --tray"
