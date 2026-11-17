"""The game code, built on the phone from the player's own files (android/gen/README): tools/gen.py translates each
module of exefs/ into C, clang compiles it the way android/gamelib_ninja.py does on a PC, and lld links libgame.so
against the runtime (libmain.so).

    build_game.py <exefs dir> <work dir> <out libgame.so> <libmain.so> <toolchain dir> [jobs]

<toolchain dir> holds bin/clang (+ ld.lld), sysroot/, res/ (clang's resource dir) and rt/ (runtime headers). Progress
on stdout: "phase <name> <done> <total>"; a failure: "error: <why>" and exit 1. Objects already built (same source
size and mtime) are kept, so a stopped build continues where it was.
"""
import concurrent.futures as cf
import os
import subprocess
import sys

MODULES = [('main', 0x1000000000, False), ('sdk', 0x1008000000, True), ('subsdk0', 0x100a000000, False),
           ('subsdk1', 0x100b000000, False)]
GIANT_LABELS = 1000
HOT_GIANTS = {'main_1654', 'main_033', 'main_1611', 'main_1590', 'main_1574', 'main_1236', 'main_1636', 'main_080',
              'main_039'}
HERE = os.path.dirname(os.path.abspath(__file__))


def say(*a):
    print(*a, flush=True)


def giant(path):
    labels = defs = 0
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            if line.startswith('L_'):
                labels += 1
            elif line.startswith('void f_') and '(Cpu *c) {' in line:
                defs += 1
                if defs > 1:
                    return False
    return labels > GIANT_LABELS


def main():
    exefs, work, out, libmain, tc = sys.argv[1:6]
    jobs = int(sys.argv[6]) if len(sys.argv) > 6 else max(1, (os.cpu_count() or 4) - 2)
    clang = os.path.join(tc, 'bin', 'clang')
    common = ['--target=aarch64-linux-android33', '--sysroot=' + os.path.join(tc, 'sysroot'),
              '-resource-dir=' + os.path.join(tc, 'res'), '-fPIC', '-fno-strict-aliasing', '-Wno-return-type',
              '-Wno-unused-label', '-fvisibility=hidden', '-I' + os.path.join(tc, 'rt')]
    gen = os.path.join(HERE, 'tools', 'gen.py')
    hle = os.path.join(HERE, 'runtime', 'hle', 'hle_symbols.txt')
    os.makedirs(work, exist_ok=True)

    # 1. translate (the four modules side by side: each is one Python process)
    say('phase translate 0 4')
    procs = []
    for name, base, use_hle in MODULES:
        src = os.path.join(exefs, name)
        dst = os.path.join(work, 'gen', name)
        if os.path.isfile(os.path.join(dst, '.done')):
            continue
        cmd = [sys.executable, gen, name, src, hex(base), dst, '--simd', 'asm'] + (['--hle', hle] if use_hle else [])
        log = open(os.path.join(work, 'gen_%s.log' % name), 'w')
        procs.append((name, dst, subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT), log))
    for i, (name, dst, p, log) in enumerate(procs):
        if p.wait() != 0:
            say('error: translating %s failed (see gen_%s.log)' % (name, name))
            sys.exit(1)
        log.close()
        open(os.path.join(dst, '.done'), 'w').close()
        say('phase translate %d 4' % (i + 1))

    # 2. compile
    units = []
    for name, _, _ in MODULES:
        d = os.path.join(work, 'gen', name)
        for f in sorted(os.listdir(d)):
            if f.endswith('.c'):
                units.append(os.path.join(d, f))
    objdir = os.path.join(work, 'obj')
    os.makedirs(objdir, exist_ok=True)

    def compile_one(src):
        stem = os.path.splitext(os.path.basename(src))[0]
        obj = os.path.join(objdir, stem + '.o')
        if os.path.isfile(obj) and os.path.getmtime(obj) >= os.path.getmtime(src):
            return obj, None
        opt = '-O0' if giant(src) and stem not in HOT_GIANTS else '-O1'
        r = subprocess.run([clang] + common + [opt, '-DGUEST_VOLATILE', '-c', src, '-o', obj + '.tmp'],
                           capture_output=True, text=True)
        if r.returncode != 0:
            return obj, r.stderr[-2000:]
        os.replace(obj + '.tmp', obj)
        return obj, None

    objs, done = [], 0
    say('phase compile 0 %d' % len(units))
    with cf.ThreadPoolExecutor(jobs) as ex:
        for obj, err in ex.map(compile_one, units):
            if err:
                say('error: compiling %s: %s' % (os.path.basename(obj), err.strip().splitlines()[-1] if err.strip() else '?'))
                sys.exit(1)
            objs.append(obj)
            done += 1
            if done % 10 == 0 or done == len(units):
                say('phase compile %d %d' % (done, len(units)))

    # 3. the entry (its id: android-<the game's main build ID>, as gamelib_ninja.py) and the link
    with open(os.path.join(exefs, 'main'), 'rb') as f:
        f.seek(0x40)
        full_id = 'android-' + f.read(8).hex().upper()
    entry = os.path.join(objdir, 'gamelib_entry.o')
    r = subprocess.run([clang] + common + ['-O1', '-DGAMELIB_ID="%s"' % full_id, '-c',
                                           os.path.join(HERE, 'tools', 'gamelib_entry.c'), '-o', entry],
                       capture_output=True, text=True)
    if r.returncode != 0:
        say('error: the entry: ' + r.stderr[-500:])
        sys.exit(1)
    say('phase link 0 1')
    rsp = os.path.join(work, 'link.rsp')
    with open(rsp, 'w') as f:
        f.write('\n'.join(objs + [entry]))
    r = subprocess.run([clang, '--target=aarch64-linux-android33', '--sysroot=' + os.path.join(tc, 'sysroot'),
                        '-resource-dir=' + os.path.join(tc, 'res'), '-shared', '-fuse-ld=lld',
                        '-Wl,-soname,libgame.so', '-o', out + '.tmp', '@' + rsp, libmain, '-lm'],
                       capture_output=True, text=True)
    if r.returncode != 0:
        say('error: linking: ' + r.stderr[-1500:])
        sys.exit(1)
    os.replace(out + '.tmp', out)
    say('phase link 1 1')
    say('done %s' % full_id)


if __name__ == '__main__':
    main()
