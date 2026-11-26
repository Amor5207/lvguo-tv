param(
    [ValidateSet("x64", "arm64", "all")]
    [string]$Arch = "x64"
)

$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$RustDir = Join-Path $RootDir "rust"
$DistDir = Join-Path $RootDir "dist"

$PubspecPath = Join-Path $RootDir "pubspec.yaml"
$AppVersion = "2.1.0"
if (Test-Path $PubspecPath) {
    $verMatch = Select-String -Path $PubspecPath -Pattern '^\s*version:\s*([0-9]+\.[0-9]+\.[0-9]+)' | Select-Object -First 1
    if ($verMatch) {
        $AppVersion = $verMatch.Matches[0].Groups[1].Value
    }
}

$env:PATH = "$env:USERPROFILE\.cargo\bin;$env:LOCALAPPDATA\Programs\Inno Setup 6;" + $env:PATH
if ($env:CI -ne "true") {
    if (-not $env:PUB_HOSTED_URL) { $env:PUB_HOSTED_URL = "https://pub.flutter-io.cn" }
    if (-not $env:FLUTTER_STORAGE_BASE_URL) { $env:FLUTTER_STORAGE_BASE_URL = "https://storage.flutter-io.cn" }
}

$IsccCandidates = @(
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
    "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
    "C:\Program Files\Inno Setup 6\ISCC.exe"
)
$Iscc = $IsccCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $Iscc) {
    throw "未找到 Inno Setup 6 (ISCC.exe)"
}

function Test-WinArm64Capable {
    $hostIsArm64 = ($env:PROCESSOR_ARCHITECTURE -eq "ARM64")
    $rustupCmd = Get-Command rustup -ErrorAction SilentlyContinue
    $hasRustTarget = $false
    if ($rustupCmd) {
        $targets = & rustup target list --installed 2>$null
        $hasRustTarget = ($targets -contains "aarch64-pc-windows-msvc")
    }
    $hasMsvcArm64 = $false
    $pf86 = ${env:ProgramFiles(x86)}
    if ($pf86) {
        $vsWhere = Join-Path $pf86 "Microsoft Visual Studio\Installer\vswhere.exe"
        if (Test-Path $vsWhere) {
            $vsPath = & $vsWhere -latest -products * -property installationPath 2>$null | Select-Object -First 1
            if ($vsPath) {
                $armTools = Get-ChildItem -Path (Join-Path $vsPath "VC\Tools\MSVC\*\bin\Host*\arm64") -ErrorAction SilentlyContinue
                $hasMsvcArm64 = ($armTools.Count -gt 0)
            }
        }
    }
    return ($hostIsArm64 -and $hasRustTarget -and $hasMsvcArm64)
}

function Build-Target([string]$TargetArch) {
    if ($TargetArch -eq "arm64" -and -not (Test-WinArm64Capable)) {
        $msg = "当前主机 ($env:PROCESSOR_ARCHITECTURE) 缺少原生 Windows ARM64 构建环境 (Flutter ARM64 + MSVC ARM64 + Rust aarch64-pc-windows-msvc)。请在 ARM64 Runner 或 GitHub Actions (windows-11-arm) 中执行。"
        if ($Arch -eq "all") {
            Write-Warning "[SKIP arm64] $msg"
            return
        }
        throw $msg
    }

    $RustTarget = if ($TargetArch -eq "arm64") { "aarch64-pc-windows-msvc" } else { "x86_64-pc-windows-msvc" }
    $ReleaseDir = Join-Path $RootDir "build\windows\$TargetArch\runner\Release"

    Write-Host "`n==> [1/4] ($TargetArch) 编译 Rust 核心 (lvguo_core.dll)..." -ForegroundColor Cyan
    Push-Location $RustDir
    $rustupCmd = Get-Command rustup -ErrorAction SilentlyContinue
    if ($rustupCmd) {
        cargo build --release --target $RustTarget
        $RustDll = Join-Path $RustDir "target\$RustTarget\release\lvguo_core.dll"
    } else {
        cargo build --release
        $RustDll = Join-Path $RustDir "target\release\lvguo_core.dll"
    }
    if ($LASTEXITCODE -ne 0) { throw "cargo build --release ($TargetArch) 失败" }
    Pop-Location

    if (-not (Test-Path $RustDll)) {
        throw "未找到编译产物: $RustDll"
    }

    Write-Host "==> [2/4] ($TargetArch) 编译 Flutter Windows 发布版..." -ForegroundColor Cyan
    Push-Location $RootDir
    flutter build windows --release
    if ($LASTEXITCODE -ne 0) { throw "flutter build windows --release ($TargetArch) 失败" }
    Pop-Location

    if (-not (Test-Path $ReleaseDir)) {
        throw "未找到 Flutter Release 目录: $ReleaseDir"
    }

    Write-Host "==> [3/4] ($TargetArch) 注入 Rust 核心 DLL 到 Release 目录..." -ForegroundColor Cyan
    Copy-Item -Path $RustDll -Destination (Join-Path $ReleaseDir "lvguo_core.dll") -Force

    New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

    Write-Host "==> [4/4] ($TargetArch) 打包 Inno Setup 安装引导程序..." -ForegroundColor Cyan
    $IssFile = Join-Path $RootDir "installer\windows\lvguo-setup.iss"
    & $Iscc "/DArch=$TargetArch" "/DAppVersion=$AppVersion" $IssFile
    if ($LASTEXITCODE -ne 0) { throw "Inno Setup 打包 ($TargetArch) 失败" }

    $SetupExe = Join-Path $DistDir "LvguoPC-Setup-$AppVersion-win-$TargetArch.exe"
    Write-Host "`n[$TargetArch] 打包完成：" -ForegroundColor Green
    Get-Item (Join-Path $ReleaseDir "lvguo_desktop.exe"), (Join-Path $ReleaseDir "lvguo_core.dll"), $SetupExe |
        Select-Object FullName, Length
}

$Targets = if ($Arch -eq "all") { @("x64", "arm64") } else { @($Arch) }
foreach ($t in $Targets) {
    Build-Target $t
}
