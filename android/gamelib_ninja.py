"""Writes build/android-game/build.ninja: the game code library (libgame.so) for Android arm64 from generated-android/
(tools/gen.py at runtime/rt.h's shifted bases, --simd asm), compiled with the NDK's clang the way the start screen
builds it (runtime/gamebuild.c): -O1 -DGUEST_VOLATILE, giant functions (over 1000 labels) at -O0. Linked against
libmain.so (the runtime), whose functions the game code calls.

    python android/gamelib_ninja.py && ninja -C build/android-game
"""
import glob
import os
import re
import subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GEN = os.path.join(ROOT, 'generated-android')
OUT = os.path.join(ROOT, 'build', 'android-game')
NDK = os.environ.get('ANDROID_NDK', os.path.join(os.environ['LOCALAPPDATA'], 'Android', 'Sdk', 'ndk', '28.2.13676358'))
CLANG = os.path.join(NDK, 'toolchains', 'llvm', 'prebuilt', 'windows-x86_64', 'bin', 'clang.exe').replace('\\', '/')
TARGET = '--target=aarch64-linux-android33'
GIANT_LABELS = 1000
# Giant files that still run every frame (found with simpleperf on the Odin 3 in Jubilife at 60 fps): -O1 like the rest
# (at -O0 they cost ~3x; 2.7% of the process's CPU between them)
HOT_GIANTS = {'main_1654', 'main_033', 'main_1611', 'main_1590', 'main_1574', 'main_1236', 'main_1636', 'main_080',
              'main_039'}

# GAMELIB_ID (runtime gamelib.c is built with GAMELIB_ID="android", android/CMakeLists.txt) + the game's main build ID
with open(os.path.join(ROOT, 'extracted', 'exefs', 'main'), 'rb') as f:
    f.seek(0x40)
    build_id = f.read(8).hex().upper()
FULL_ID = f'android-{build_id}'


def giant(path):
    labels, defs = 0, 0
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            if line.startswith('L_'):
                labels += 1
            elif line.startswith('void f_') and '(Cpu *c) {' in line:
                defs += 1
                if defs > 1:
                    return False
    return labels > GIANT_LABELS


def esc(p):
    return p.replace('\\', '/').replace(':', '$:').replace(' ', '$ ')


def main():
    os.makedirs(OUT, exist_ok=True)
    srcs = sorted(glob.glob(os.path.join(GEN, '*', '*.c')))
    common = (f'{TARGET} -fPIC -fno-strict-aliasing -Wno-return-type -Wno-unused-label -fvisibility=hidden '
              f'-I{esc(os.path.join(ROOT, "runtime"))}')
    libmain = os.path.join(ROOT, 'build', 'android-rt', 'libmain.so')
    lines = [
        f'cflags = {common}',
        f'rule cc1\n  command = {CLANG} $cflags -O1 -DGUEST_VOLATILE -c $in -o $out\n  description = O1 $in',
        f'rule cc0\n  command = {CLANG} $cflags -O0 -DGUEST_VOLATILE -c $in -o $out\n  description = O0 $in',
        f'rule entry\n  command = {CLANG} $cflags -O1 -DGAMELIB_ID=\\"{FULL_ID}\\" -c $in -o $out',
        f'rule link\n  command = {CLANG} {TARGET} -shared -fuse-ld=lld -Wl,-soname,libgame.so -o $out @$out.rsp '
        f'{esc(libmain)} -lm\n  rspfile = $out.rsp\n  rspfile_content = $in\n  description = link $out',
    ]
    objs, n0 = [], 0
    for s in srcs:
        rel = os.path.relpath(s, GEN)
        o = os.path.join(OUT, 'obj', os.path.splitext(rel)[0] + '.o')
        g = giant(s) and os.path.splitext(os.path.basename(s))[0] not in HOT_GIANTS
        n0 += g
        lines.append(f'build {esc(o)}: {"cc0" if g else "cc1"} {esc(s)}')
        objs.append(o)
    eo = os.path.join(OUT, 'obj', 'gamelib_entry.o')
    lines.append(f'build {esc(eo)}: entry {esc(os.path.join(ROOT, "tools", "gamelib_entry.c"))}')
    objs.append(eo)
    lines.append(f'build {esc(os.path.join(OUT, "libgame.so"))}: link {" ".join(esc(o) for o in objs)} | {esc(libmain)}')
    with open(os.path.join(OUT, 'build.ninja'), 'w', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    print(f'{len(srcs)} sources ({n0} giant at -O0), id {FULL_ID}')


if __name__ == '__main__':
    main()
