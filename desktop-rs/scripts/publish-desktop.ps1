param(
  [string]$Version = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$config = Get-Content "src-tauri/tauri.conf.json" -Raw | ConvertFrom-Json
if (-not $Version) { $Version = $config.version }

$keyPath = "$env:USERPROFILE\.onyx\onyx-updater.key"
$passwordPath = "$env:USERPROFILE\.onyx\onyx-updater.password"
if (-not (Test-Path $keyPath)) {
  throw "Нет ключа подписи: $keyPath"
}
if (-not (Test-Path $passwordPath)) {
  throw "Нет пароля ключа: $passwordPath"
}

$env:TAURI_SIGNING_PRIVATE_KEY = $keyPath
$env:TAURI_SIGNING_PRIVATE_KEY_PASSWORD = Get-Content $passwordPath -Raw

npm run tauri build

$bundle = "src-tauri/target/release/bundle/nsis"
$installer = Get-ChildItem "$bundle/ONYX_*_x64-setup.exe" | Select-Object -First 1
if (-not $installer) { throw "NSIS-установщик не найден в $bundle" }

$signature = Get-Content "$($installer.FullName).sig" -Raw
$url = "https://github.com/ysl021/onyx/releases/download/desktop-latest/$($installer.Name)"
$latest = @{
  version = $Version
  pub_date = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
  platforms = @{
    "windows-x86_64" = @{
      signature = $signature.Trim()
      url = $url
    }
  }
} | ConvertTo-Json -Depth 10

$latestPath = Join-Path $bundle "latest.json"
$latest | Set-Content $latestPath -Encoding UTF8
Write-Host "latest.json: $latestPath"

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
  Write-Host "gh не установлен. Загрузи вручную:"
  Write-Host "  $($installer.FullName)"
  Write-Host "  $latestPath"
  exit 0
}

gh release view desktop-latest 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
  gh release create desktop-latest --title "ONYX Desktop (latest)" --notes "Автоматическая сборка ONYX Desktop."
}

gh release upload desktop-latest "$($installer.FullName)" "$latestPath" --clobber
Write-Host "Готово: https://github.com/ysl021/onyx/releases/tag/desktop-latest"
