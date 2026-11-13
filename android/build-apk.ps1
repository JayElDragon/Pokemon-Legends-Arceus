# Builds the runtime (CMake/Ninja, NDK), stages the native libraries and assembles the debug APK.
# -Install: installs it on the Odin (serial 1234cf8d) and starts it. The game code library (libgame.so) is built
# separately (android/gamelib_ninja.py + ninja -C build/android-game) and staged when present.
param([switch]$Install, [switch]$WithoutGame, [string]$Serial = '1234cf8d')
$ErrorActionPreference = 'Continue'  # (native tools write warnings to stderr; exit codes are checked)
$root = Split-Path $PSScriptRoot -Parent
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$ndk = Join-Path $sdk 'ndk\28.2.13676358'
$ninja = Join-Path $sdk 'cmake\3.31.6\bin\ninja.exe'
$env:JAVA_HOME = Join-Path $env:ProgramFiles 'Microsoft\jdk-17.0.21.21-hotspot'
$env:ANDROID_HOME = $sdk

& $ninja -C (Join-Path $root 'build\android-rt') main main_hook hook_impl
if ($LASTEXITCODE) { throw "runtime build failed ($LASTEXITCODE)" }

$jni = Join-Path $PSScriptRoot 'app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Force $jni | Out-Null
Copy-Item (Join-Path $root 'build\android-rt\libmain.so'), (Join-Path $root 'build\android-rt\SDL3\libSDL3.so') $jni -Force
$hooks = Join-Path $root 'build\android-rt\_deps\adrenotools-build\src\hook'
Copy-Item (Join-Path $hooks 'libmain_hook.so'), (Join-Path $hooks 'libhook_impl.so') $jni -Force -ErrorAction Stop
Copy-Item (Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so') $jni -Force
$game = Join-Path $root 'build\android-game\libgame.so'
if (!$WithoutGame -and (Test-Path $game)) { Copy-Item $game $jni -Force }
[string[]]$gradleOptions = if ($WithoutGame) { @('-PwithoutGame=true') } else { @() }

Push-Location $PSScriptRoot
try {
    & .\gradlew.bat assembleDebug --console=plain --no-daemon -q @gradleOptions
    if ($LASTEXITCODE) { throw "gradle failed ($LASTEXITCODE)" }
} finally { Pop-Location }
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
Write-Output $apk

if ($Install) {
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    & $adb -s $Serial install -r $apk
    & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP
    $package = if ($WithoutGame) { 'com.jay.plarecomp.importer' } else { 'com.jay.plarecomp' }
    & $adb -s $Serial shell am start -n "$package/com.jay.plarecomp.PlaActivity"
}
