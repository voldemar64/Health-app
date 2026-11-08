# Dot-source this script from PowerShell. Changes apply only to the current process.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolingRoot = Join-Path $projectRoot '.tooling'
$jdkPath = Join-Path $toolingRoot 'jdk-21.0.13+9'
if (-not (Test-Path -LiteralPath (Join-Path $jdkPath 'bin/java.exe'))) {
    throw 'Temurin JDK 21.0.13+9 not found in .tooling. See apps/android/README.md.'
}
$env:JAVA_HOME = $jdkPath
$env:ANDROID_HOME = Join-Path $toolingRoot 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $toolingRoot 'android-user'
$env:ANDROID_AVD_HOME = Join-Path $toolingRoot 'avd'
$env:GRADLE_USER_HOME = Join-Path $toolingRoot 'gradle-user'
$env:PATH = "$env:JAVA_HOME/bin;$env:ANDROID_HOME/platform-tools;$env:ANDROID_HOME/emulator;$env:ANDROID_HOME/cmdline-tools/19.0/bin;$env:PATH"
