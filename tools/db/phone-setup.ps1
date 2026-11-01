# LifeLog 폰 설정: 관리자 접속으로 코어 마이그레이션 → 폰 기기 비밀번호 재발급 → adb로 앱에 접속 정보를 넘긴다.
# 비밀번호는 프롬프트에서 받고 어디에도 저장하지 않는다. 기기 비밀번호는 화면에 찍지 않고 adb 인텐트로만 앱에 전달한다.
# 사용자가 일반 PowerShell에서 직접 실행한다(Claude 도구 셸에서 돌리지 않는다).
#
#   powershell -ExecutionPolicy Bypass -File tools/db/phone-setup.ps1 -ProjectRef ektnxxskcpngsiwmbftb
#   옵션: -Device phone -SkipMigrate -SkipReset(비밀번호를 다시 만들지 않고 앱만 연다)
param(
    [Parameter(Mandatory = $true)][string]$ProjectRef,
    [string]$DbHost = "aws-0-ap-northeast-1.pooler.supabase.com",
    [int]$Port = 5432,
    [string]$Database = "postgres",
    [string]$Device = "phone",
    [switch]$SkipMigrate,
    [switch]$SkipReset
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$pkg = "dev.hyeseong.lifelog.collector"

$secure = Read-Host "Supabase DB 비밀번호 (postgres)" -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringUni([Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($secure))
$env:LIFELOG_ADMIN_DSN = "host=$DbHost port=$Port dbname=$Database user=postgres.$ProjectRef password=$plain sslmode=require"
$plain = $null

if (-not $SkipMigrate) {
    Write-Host "== 1. 코어 스키마 마이그레이션"
    python tools/db/migrate.py
    if ($LASTEXITCODE -ne 0) { throw "마이그레이션 실패" }
}

$devicePw = ""
if (-not $SkipReset) {
    Write-Host "== 2. 기기 '$Device' 비밀번호 재발급"
    $devicePw = python -c "import os,psycopg; c=psycopg.connect(os.environ['LIFELOG_ADMIN_DSN'],autocommit=True); print(c.execute('SELECT lifelog.reset_device_password(%s)',('$Device',)).fetchone()[0])"
    if ($LASTEXITCODE -ne 0 -or -not $devicePw) { throw "비밀번호 재발급 실패" }
}
$env:LIFELOG_ADMIN_DSN = $null

Write-Host "== 3. 폰 앱에 접속 정보 전달 (adb)"
& $adb shell am force-stop $pkg | Out-Null
$args = "am start -n $pkg/.ui.MainActivity --es device '$Device' --es host '$DbHost' --es port '$Port' --es database '$Database' --es tenant '$ProjectRef'"
if ($devicePw) { $args += " --es password '$devicePw'" }
& $adb shell $args | Out-Null
$devicePw = $null
$args = $null
Write-Host "== 완료. 폰 화면에서 '저장하고 연결 확인'을 누르세요."
