param([string]$Ndk = $env:ANDROID_NDK_HOME, [string]$Gradle = ".\gradlew.bat", [switch]$SkipNative)
$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    if (-not $SkipNative) {
        if (-not $Ndk -or -not (Test-Path -LiteralPath "$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe")) { throw "Set ANDROID_NDK_HOME or pass -Ndk pointing to Android NDK r27d or newer." }
        $compilerBin = "$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
        $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = "$compilerBin/aarch64-linux-android26-clang.cmd"
        $env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = "$compilerBin/x86_64-linux-android26-clang.cmd"
        & cargo test --manifest-path native/Cargo.toml
        if ($LASTEXITCODE) { throw "Rust tests failed" }
        & rustup target add aarch64-linux-android x86_64-linux-android
        if ($LASTEXITCODE) { throw "Android Rust target installation failed" }
        & cargo build --release --lib --manifest-path native/Cargo.toml --target aarch64-linux-android --target x86_64-linux-android
        if ($LASTEXITCODE) { throw "Native compilation failed" }
        foreach ($target in @(@("aarch64-linux-android", "arm64-v8a"), @("x86_64-linux-android", "x86_64"))) {
            $destination = "app/src/main/jniLibs/$($target[1])"
            New-Item -ItemType Directory -Force -Path $destination | Out-Null
            Copy-Item -LiteralPath "native/target/$($target[0])/release/libytm_core.so" -Destination "$destination/libytm_core.so"
        }
    }
    & $Gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
    if ($LASTEXITCODE) { throw "Android build or checks failed" }
    Write-Output "$projectRoot/app/build/outputs/apk/debug/app-debug.apk"
} finally { Pop-Location }
