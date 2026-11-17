"""Stage the Android ARM64 compiler payload for build-apk.ps1 -ToolchainDir."""

import argparse
import hashlib
from pathlib import Path
import shutil
from zipfile import ZIP_DEFLATED, ZipFile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("python_stage", "clang", "lld", "ndk_llvm", "output"):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    runtime = args.python_stage / "lib"
    native = args.output / "jniLibs/arm64-v8a"
    assets = args.output / "assets"
    native.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    libraries = ("libpython3.14.so", "libunicorn.so", "libcapstone.so", "liblz4.so",
                 "libcrypto_python.so", "libssl_python.so", "libsqlite3_python.so",
                 "libcrypto.so", "libssl.so", "libsqlite3.so")
    executables = {runtime / "pyrun": "libpla_pyrun.so", args.clang: "libpla_clang.so",
                   args.lld: "libpla-gnu-lld.so"}
    for source, name in {**{runtime / name: name for name in libraries}, **executables}.items():
        with source.open("rb") as binary:
            header = binary.read(20)
        if header[:6] != b"\x7fELF\x02\x01" or header[18:20] != b"\xb7\x00":
            raise ValueError(f"Expected an AArch64 ELF: {source}")
        shutil.copyfile(source, native / name)

    with ZipFile(assets / "pla-toolchain.zip", "w", ZIP_DEFLATED) as archive:
        digest = hashlib.sha256()

        def add_file(source, name):
            archive.write(source, name)
            digest.update(str(name).encode())
            with source.open("rb") as file:
                digest.update(hashlib.file_digest(file, "sha256").digest())

        def add_tree(source, prefix, keep=lambda rel: True):
            if not source.is_dir():
                raise FileNotFoundError(source)
            for file in sorted(source.rglob("*")):
                rel = file.relative_to(source)
                if file.is_file() and "__pycache__" not in file.parts and keep(rel.as_posix()):
                    add_file(file, prefix / rel)

        # only what an aarch64 Android 33 shared library links with (the NDK has every API level and architecture)
        def sysroot_lib(rel):
            return rel.startswith("33/") or rel in ("libcompiler_rt-extras.a", "libm.a")

        def clang_lib(rel):
            return rel in ("libclang_rt.builtins-aarch64-android.a", "aarch64/libunwind.a", "aarch64/libatomic.a")

        add_tree(args.python_stage / "python", Path("python"))
        for package in ("capstone", "unicorn", "lz4"):
            add_tree(repo / "android/gen" / package, Path("gen") / package)
        for module in ("gen", "nso", "analyze", "lifter", "flagfuse", "regpromote",
                       "za_patches", "pla_patches", "patches", "game_addresses", "simd_c"):
            source = repo / "tools" / f"{module}.py"
            add_file(source, f"gen/tools/{source.name}")
        for source in [repo / "runtime/cpu.h", repo / "runtime/gamelib.h",
                       *sorted((repo / "runtime").glob("simd*.h")),
                       repo / "tools/gamelib_entry.c", repo / "runtime/hle/hle_symbols.txt"]:
            add_file(source, f"gamelib-src/{source.name}")
        sysroot = args.ndk_llvm / "sysroot/usr"
        add_tree(sysroot / "include", Path("sysroot/usr/include"))
        add_tree(sysroot / "lib/aarch64-linux-android", Path("sysroot/usr/lib/aarch64-linux-android"), sysroot_lib)
        resource_dirs = sorted((args.ndk_llvm / "lib/clang").iterdir())
        add_tree(resource_dirs[-1] / "include", Path("clang-res/include"))
        add_tree(resource_dirs[-1] / "lib/linux", Path("clang-res/lib/linux"), clang_lib)
        archive.writestr("toolchain-id.txt", digest.hexdigest() + "\n")


if __name__ == "__main__":
    main()
