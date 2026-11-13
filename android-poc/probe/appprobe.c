// The same probe inside an app process (zygote-forked, ART running): which guest layouts can the runtime
// claim when its library is loaded? Logs to logcat with the tag "vaprobe".
#define _GNU_SOURCE
#include <android/log.h>
#include <android/native_activity.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif
#define L(...) __android_log_print(ANDROID_LOG_INFO, "vaprobe", __VA_ARGS__)

static int try_map(const char *what, uint64_t lo, uint64_t hi) {
    void *p = mmap((void *)(uintptr_t)lo, hi - lo, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED_NOREPLACE, -1, 0);
    int ok = p != MAP_FAILED && p == (void *)(uintptr_t)lo;
    int err = errno;
    if (ok) {  // touch both ends
        *(volatile uint64_t *)(uintptr_t)lo = 1;
        *(volatile uint64_t *)(uintptr_t)(hi - 8) = 2;
    }
    L("%-30s %010llx-%010llx %6.1f GB: %s", what, (unsigned long long)lo, (unsigned long long)hi,
      (hi - lo) / 1073741824.0, ok ? "OK" : (p == MAP_FAILED ? strerror(err) : "moved"));
    if (p != MAP_FAILED) munmap(p, hi - lo);
    return ok;
}

static void dump_range(uint64_t lo, uint64_t hi) {
    FILE *f = fopen("/proc/self/maps", "r");
    char line[512];
    int n = 0;
    while (f && fgets(line, sizeof line, f)) {
        unsigned long long a, b;
        if (sscanf(line, "%llx-%llx", &a, &b) != 2) continue;
        if (b > lo && a < hi && n++ < 40) { line[strcspn(line, "\n")] = 0; L("  in range: %s", line); }
    }
    if (n > 40) L("  ... %d mappings in range", n);
    if (f) fclose(f);
}

static void run(const char *when) {
    L("==== %s (pid %d)", when, getpid());
    L("-- mappings in 0x1000000000..0x7320000000:");
    dump_range(0x1000000000ull, 0x7320000000ull);
    try_map("rt.h Linux (0x70-0x80)", 0x7000000000ull, 0x8000000000ull);
    try_map("main window (0x70-0x732)", 0x7000000000ull, 0x7320000000ull);
    try_map("alias at 0x40 (48 GB)", 0x4000000000ull, 0x4c00000000ull);
    try_map("one piece 0x40-0x732", 0x4000000000ull, 0x7320000000ull);
    try_map("one piece 0x10-0x732", 0x1000000000ull, 0x7320000000ull);
    try_map("shifted layout 0x10-0x20", 0x1000000000ull, 0x2000000000ull);
    try_map("shifted layout 0x20-0x30", 0x2000000000ull, 0x3000000000ull);
    try_map("shifted layout 0x08-0x18", 0x0800000000ull, 0x1800000000ull);
}

__attribute__((constructor(101))) static void early(void) { run("library constructor"); }

void ANativeActivity_onCreate(ANativeActivity *a, void *state, size_t size) {
    (void)state; (void)size;
    run("onCreate");
    ANativeActivity_finish(a);
}
