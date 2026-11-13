// Feasibility probe for PokeL-ARecomp on Android: can this process own the guest window at the
// Switch's own addresses (guest == host, runtime/rt.h)? Reports the kernel's VA size, what already
// sits in 0x70_0000_0000..0x80_0000_0000, and tries the exact reservations the runtime makes.
#define _GNU_SOURCE
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>
#include <errno.h>
#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

static int try_map(const char *what, uint64_t lo, uint64_t hi) {
    void *p = mmap((void *)(uintptr_t)lo, hi - lo, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED_NOREPLACE, -1, 0);
    int ok = p != MAP_FAILED && p == (void *)(uintptr_t)lo;
    printf("%-34s %010llx-%010llx %6.1f GB: %s\n", what, (unsigned long long)lo, (unsigned long long)hi,
           (hi - lo) / 1073741824.0, ok ? "OK" : (p == MAP_FAILED ? strerror(errno) : "moved"));
    if (p != MAP_FAILED) {
        if (ok) { volatile uint64_t *q = (uint64_t *)(uintptr_t)lo; *q = 0x1234; ok = *q == 0x1234; }
        munmap(p, hi - lo);
    }
    return ok;
}
#include <errno.h>

int main(void) {
    printf("page size %ld\n", sysconf(_SC_PAGESIZE));
    uint64_t top = 0;
    FILE *f = fopen("/proc/self/maps", "r");
    char line[512];
    printf("-- mappings inside 0x7000000000..0x8000000000:\n");
    while (f && fgets(line, sizeof line, f)) {
        unsigned long long a, b;
        if (sscanf(line, "%llx-%llx", &a, &b) != 2) continue;
        if (b > top) top = b;
        if (b > 0x7000000000ull && a < 0x8000000000ull) fputs(line, stdout);
    }
    if (f) fclose(f);
    int bits = 0;
    while (bits < 64 && (1ull << bits) < top) bits++;
    printf("-- highest mapping ends at %llx -> user VA is ~%d bits\n", (unsigned long long)top, bits);
    int a = try_map("runtime window (rt.h, Linux)", 0x7000000000ull, 0x8000000000ull);
    int b = try_map("macOS-size window (no alias)", 0x7000000000ull, 0x7320000000ull);
    int c = try_map("alias region only (Arceus 48 GB)", 0x7400000000ull, 0x8000000000ull);
    printf("RESULT full=%d small=%d alias=%d\n", a, b, c);
    return 0;
}
