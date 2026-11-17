# Android on-device game compiler

The compiler payload contains Python, the source translator, Clang, LLD and the
NDK sysroot. It is independent of any particular game dump. The launcher runs
the translator and compiler on the imported executables and uses the runtime's
build cache to resume interrupted builds. It keeps the generated library in the
app's private data directory.

Stage the payload using the Android ARM64 Python staging directory and the
Android ARM64 LLVM 19 build. The NDK path points to its LLVM prebuilt directory.

```powershell
python android/gen/stage-toolchain.py <python-stage> <clang> <lld> <ndk-llvm> <payload>
android/build-apk.ps1 -WithoutGame -ToolchainDir <payload>
```

The test must use an importer installation without a manually installed
`files/libgame.so`. Import the base game, update and keys. Keep the app open
while it translates and compiles the code. Verify the Play button, then restart
the app and verify that the generated library still loads.
