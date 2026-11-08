param([switch]$AcceptSdkLicenses)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolingRoot = Join-Path $projectRoot '.tooling'
$downloadRoot = Join-Path $projectRoot '.cache'
New-Item -ItemType Directory -Force $toolingRoot, $downloadRoot | Out-Null

function Get-VerifiedArchive {
    param([string]$Url, [string]$Destination, [string]$Algorithm, [string]$ExpectedHash)
    if (-not (Test-Path -LiteralPath $Destination)) {
        Invoke-WebRequest -Uri $Url -OutFile $Destination
    }
    if ((Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash -ne $ExpectedHash) {
        throw "Checksum mismatch: $Destination. Remove this archive and retry."
    }
}

$jdkExecutable = Join-Path $toolingRoot 'jdk-21.0.13+9/bin/java.exe'
if (-not (Test-Path -LiteralPath $jdkExecutable)) {
    $jdkArchive = Join-Path $downloadRoot 'jdk21.zip'
    Get-VerifiedArchive `
        -Url 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip' `
        -Destination $jdkArchive -Algorithm SHA256 `
        -ExpectedHash 'a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9'
    Expand-Archive -LiteralPath $jdkArchive -DestinationPath $toolingRoot -Force
}

$commandToolsRoot = Join-Path $toolingRoot 'android-sdk/cmdline-tools'
$sdkManager = Join-Path $commandToolsRoot '19.0/bin/sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
    $commandArchive = Join-Path $downloadRoot 'android-commandline.zip'
    # Google's repository metadata supplies SHA-1 for this fixed archive.
    Get-VerifiedArchive `
        -Url 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' `
        -Destination $commandArchive -Algorithm SHA1 `
        -ExpectedHash '54a582f3bf73e04253602f2d1c80bd5868aac115'
    New-Item -ItemType Directory -Force $commandToolsRoot | Out-Null
    Expand-Archive -LiteralPath $commandArchive -DestinationPath $commandToolsRoot -Force
    Rename-Item -LiteralPath (Join-Path $commandToolsRoot 'cmdline-tools') -NewName '19.0'
}

. (Join-Path $PSScriptRoot 'android-env.ps1')
if ($AcceptSdkLicenses) {
    1..100 | ForEach-Object { 'y' } | & $sdkManager --licenses
} else {
    & $sdkManager --licenses
}
if ($LASTEXITCODE -ne 0) { throw 'SDK license review failed.' }

& $sdkManager 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0' 'emulator' 'system-images;android-36;google_apis;x86_64'
if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed.' }

New-Item -ItemType Directory -Force $env:ANDROID_AVD_HOME | Out-Null
$avdManager = Join-Path $commandToolsRoot '19.0/bin/avdmanager.bat'
$existingAvds = & $avdManager list avd -c
if ($LASTEXITCODE -ne 0) { throw 'Cannot list Android virtual devices.' }
if ($existingAvds -notcontains 'health_api36') {
    'no' | & $avdManager create avd --name health_api36 --package 'system-images;android-36;google_apis;x86_64' --device pixel_2
    if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed.' }
}

Write-Output 'Android tooling installed in .tooling. Dot-source scripts/android-env.ps1 in each new shell.'
