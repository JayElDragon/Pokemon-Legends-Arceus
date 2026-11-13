// adb test of android/gamefiles.c: gamefiles_test <prod.keys> <game.nsp|xci> [update.nsp|-] <out dir>
#include <fcntl.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
bool pla_gamefiles_import(const char *keys, int base_fd, int upd_fd, const char *out, char *err, size_t errn);
void pla_gamefiles_progress(uint64_t *done, uint64_t *total, int *phase);
static volatile int finished;
static void *watch(void *a) {
    (void)a;
    while (!finished) {
        uint64_t d, t; int ph;
        pla_gamefiles_progress(&d, &t, &ph);
        if (t) fprintf(stderr, "progress phase %d: %llu / %llu MB\n", ph, (unsigned long long)(d >> 20), (unsigned long long)(t >> 20));
        sleep(5);
    }
    return NULL;
}
int main(int argc, char **argv) {
    if (argc != 5) { fprintf(stderr, "usage: %s keys game update|- out\n", argv[0]); return 2; }
    int b = open(argv[2], O_RDONLY), u = strcmp(argv[3], "-") ? open(argv[3], O_RDONLY) : -1;
    pthread_t th; pthread_create(&th, NULL, watch, NULL);
    char err[512];
    bool ok = pla_gamefiles_import(argv[1], b, u, argv[4], err, sizeof err);
    finished = 1;
    printf("%s %s\n", ok ? "OK" : "FAILED", err);
    return ok ? 0 : 1;
}
