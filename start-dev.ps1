param(
    [string]$HBuilderX = $env:HBUILDERX_PATH,
    [switch]$Android
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$backendDirectory = Join-Path $projectRoot 'backend'
$frontendDirectory = Join-Path $projectRoot 'DC'

$java = Get-Command java -ErrorAction Stop
$javaStartInfo = [Diagnostics.ProcessStartInfo]::new()
$javaStartInfo.FileName = $java.Source
$javaStartInfo.Arguments = '-version'
$javaStartInfo.RedirectStandardError = $true
$javaStartInfo.UseShellExecute = $false
$javaProcess = [Diagnostics.Process]::Start($javaStartInfo)
$javaVersionText = $javaProcess.StandardError.ReadToEnd()
$javaProcess.WaitForExit()
if ($javaVersionText -notmatch '"(?<version>\d+)') {
    throw "无法识别 Java 版本：$javaVersionText"
}
if ([int]$Matches.version -lt 17) {
    throw "项目需要 Java 17 或更高版本，当前版本：$javaVersionText"
}

if (-not $HBuilderX) {
    $HBuilderX = Join-Path $env:USERPROFILE 'HBuilderX.5.26.2026091802\HBuilderX\HBuilderX.exe'
}
if (-not (Test-Path -LiteralPath $HBuilderX)) {
    throw "找不到 HBuilderX。请安装 5.21 或更新版本，并通过 -HBuilderX 指定 HBuilderX.exe 路径。"
}

$backendReady = $false
try {
    $health = Invoke-RestMethod -Uri 'http://localhost:8088/api/health' -TimeoutSec 2
    $backendReady = $health.status -eq 'ok'
} catch {
    $backendReady = $false
}

if (-not $backendReady) {
    $terminal = (Get-Command pwsh.exe -ErrorAction SilentlyContinue).Source
    if (-not $terminal) {
        $terminal = (Get-Command powershell.exe -ErrorAction Stop).Source
    }
    $backendCommand = "Set-Location -LiteralPath '$backendDirectory'; .\mvnw.cmd spring-boot:run"
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($backendCommand))
    Start-Process -FilePath $terminal -ArgumentList @('-NoExit', '-EncodedCommand', $encodedCommand)
    Write-Host '已在新终端启动 Java 后端，首次启动会下载 Maven 和依赖。'
} else {
    Write-Host 'Java 后端已在 http://localhost:8088 运行。'
}

if ($Android) {
    $sdkRoot = $env:ANDROID_HOME
    if (-not $sdkRoot) {
        $sdkRoot = [Environment]::GetEnvironmentVariable('ANDROID_HOME', 'User')
    }
    if (-not $sdkRoot) {
        throw '未设置 ANDROID_HOME。请按 README 中的 Android SDK 步骤配置 SDK。'
    }
    $adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
    $emulator = Join-Path $sdkRoot 'emulator\emulator.exe'
    if (-not (Test-Path -LiteralPath $adb) -or -not (Test-Path -LiteralPath $emulator)) {
        throw "在 $sdkRoot 下找不到 adb 或 Android Emulator。"
    }
    $emulatorOnline = (& $adb devices | Select-String '^emulator-\d+\s+device(?:\s|$)')
    if (-not $emulatorOnline) {
        Start-Process -FilePath $emulator -ArgumentList @('-avd', 'SynapticPulse_API35', '-no-audio')
        Write-Host '已启动 Android 模拟器 SynapticPulse_API35。'
    } else {
        Write-Host 'Android 模拟器已连接到 adb。'
    }
}

Start-Process -FilePath $HBuilderX -ArgumentList $frontendDirectory
if ($Android) {
    Write-Host '已在 HBuilderX 中打开 DC 前端工程。选择“运行到 Android App 基座”启动 Android 版。'
} else {
    Write-Host '已在 HBuilderX 中打开 DC 前端工程。选择“运行到浏览器”启动 Windows Web 版。'
}
