# VS Code 확장을 사용자 확장 폴더에 복사한다(vsce 없이). 실행 뒤 VS Code를 다시 시작하면 활성화된다.
#   powershell -ExecutionPolicy Bypass -File collector-vscode/install.ps1
$ErrorActionPreference = "Stop"
$src = $PSScriptRoot
$dest = Join-Path $env:USERPROFILE ".vscode\extensions\lifelog.lifelog-vscode-1.0.0"
if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
New-Item -ItemType Directory -Force $dest | Out-Null
Copy-Item (Join-Path $src "package.json") $dest
Copy-Item (Join-Path $src "extension.js") $dest
Write-Host "설치됨: $dest"
Write-Host "VS Code를 다시 시작하세요. 기록 파일: $env:LOCALAPPDATA\LifeLog\vscode\events.jsonl"
