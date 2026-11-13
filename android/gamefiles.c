// Game Files on Android: the player's own prod.keys, game (.nsp / .xci) and update (.nsp) made into the files the port
// runs from - <out>/exefs/ (the update's code when there is one) and <out>/romfs.bin (the base RomFS with the update
// applied: BKTR) - the same layout tools/zaimport makes on a PC. The packages are read where the player keeps them
// (file descriptors from Android's file picker, PlaActivity.java): nothing is copied first, so a 6 GB game needs ~6 GB
// free, not 12.
// The work is hactool's (third_party/hactool, ISC): its NCA code decrypts, follows the update's relocation table and
// writes the files. hactool reports errors with exit(), so it runs in a forked copy of the process: a failure ends
// only that copy, and its last stderr line is the reason shown on the setup screen.
#define _GNU_SOURCE
#define __USE_BSD 1
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include "../third_party/hactool/nca.h"
#include "../third_party/hactool/extkeys.h"
#include "../third_party/hactool/pki.h"
#include "../third_party/hactool/settings.h"

// ---- shared with the forked worker (an anonymous shared mapping made before the fork)
typedef struct {
    volatile uint64_t done, total;  // the file being written
    volatile int phase;             // 0 reading the packages, 1 the code (exefs), 2 the game data (romfs.bin)
} Shared;
static Shared *shared;

// hactool's save loop (third_party/hactool/nca.c nca_save_section_file) reports here
void hac_progress(uint64_t done, uint64_t total) {
    if (shared) shared->done = done, shared->total = total;
}

// ---- a FILE* over a byte range of a package (an NCA inside the .nsp / .xci): hactool reads NCAs from offset 0
typedef struct { int fd; off64_t base, size, pos; } Window;
// (bionic's funopen64: the BSD form of a custom FILE)
static int win_read(void *c, char *buf, int n) {
    Window *w = c;
    if (w->pos >= w->size) return 0;
    if ((off64_t)n > w->size - w->pos) n = (int)(w->size - w->pos);
    ssize_t r = pread64(w->fd, buf, (size_t)n, w->base + w->pos);
    if (r > 0) w->pos += r;
    return (int)r;
}
static off64_t win_seek(void *c, off64_t off, int whence) {
    Window *w = c;
    off64_t p = whence == SEEK_SET ? off : whence == SEEK_CUR ? w->pos + off : w->size + off;
    if (p < 0) { errno = EINVAL; return -1; }
    w->pos = p;
    return p;
}
static int win_close(void *c) { free(c); return 0; }
#ifndef __ANDROID__  // (glibc - a desktop test build: fopencookie instead)
static ssize_t win_read_g(void *c, char *buf, size_t n) { return win_read(c, buf, n > (1u << 30) ? (1 << 30) : (int)n); }
static int win_seek_g(void *c, off64_t *off, int whence) {
    off64_t p = win_seek(c, *off, whence);
    if (p < 0) return -1;
    *off = p;
    return 0;
}
#else
static fpos64_t win_seek_b(void *c, fpos64_t off, int whence) { return (fpos64_t)win_seek(c, (off64_t)off, whence); }
#endif
static FILE *window(int fd, off64_t base, off64_t size) {
    Window *w = malloc(sizeof *w);
    *w = (Window){fd, base, size, 0};
#ifdef __ANDROID__
    FILE *f = funopen64(w, win_read, NULL, win_seek_b, win_close);
#else
    cookie_io_functions_t io = {.read = win_read_g, .seek = win_seek_g, .close = win_close};
    FILE *f = fopencookie(w, "rb", io);
#endif
    if (f) setvbuf(f, NULL, _IOFBF, 1 << 20);
    return f;
}

// ---- the files inside a package: .nsp (PFS0) or .xci (its secure HFS0 partition)
typedef struct { char name[96]; uint64_t off, size; } Entry;
static int read_at(int fd, void *b, size_t n, off64_t at) { return pread64(fd, b, n, at) == (ssize_t)n; }

static int list_fs(int fd, off64_t at, bool hfs, Entry *out, int max) {
    uint8_t h[16];
    if (!read_at(fd, h, 16, at) || memcmp(h, hfs ? "HFS0" : "PFS0", 4)) return -1;
    uint32_t n = *(uint32_t *)(h + 4), strsz = *(uint32_t *)(h + 8), esz = hfs ? 0x40 : 0x18;
    if (n > 4096 || strsz > (1u << 20)) return -1;
    uint8_t *tab = malloc(n * esz + strsz);
    if (!read_at(fd, tab, n * esz + strsz, at + 16)) { free(tab); return -1; }
    off64_t data = at + 16 + n * esz + strsz;
    int k = 0;
    for (uint32_t i = 0; i < n && k < max; i++) {
        uint8_t *e = tab + i * esz;
        uint32_t no = *(uint32_t *)(e + 16);
        if (no >= strsz) continue;
        snprintf(out[k].name, sizeof out[k].name, "%s", (char *)tab + n * esz + no);
        out[k].off = data + *(uint64_t *)e, out[k].size = *(uint64_t *)(e + 8);
        k++;
    }
    free(tab);
    return k;
}

static int list_package(int fd, Entry *out, int max) {
    uint8_t m[4];
    if (read_at(fd, m, 4, 0) && !memcmp(m, "PFS0", 4)) return list_fs(fd, 0, false, out, max);
    uint8_t head[0x40];
    if (!read_at(fd, head, sizeof head, 0x100) || memcmp(head, "HEAD", 4)) return -1;
    off64_t root = (off64_t)*(uint64_t *)(head + 0x30);
    Entry parts[8];
    int np = list_fs(fd, root, true, parts, 8);
    for (int i = 0; i < np; i++)
        if (!strcmp(parts[i].name, "secure")) return list_fs(fd, parts[i].off, true, out, max);
    return -1;
}

static bool ends_with(const char *s, const char *e) {
    size_t a = strlen(s), b = strlen(e);
    return a >= b && !strcasecmp(s + a - b, e);
}

typedef struct { int fd; Entry e; uint64_t title; bool found; } Program;

// the package's program NCA (the game's code and data), and its tickets' title keys into the settings
static bool scan(int fd, hactool_ctx_t *tool, Program *p, const char *what) {
    static Entry ents[512];
    int n = list_package(fd, ents, 512);
    if (n <= 0) { fprintf(stderr, "error: the %s isn't an .nsp or .xci the port can read\n", what); return false; }
    for (int i = 0; i < n; i++)
        if (ends_with(ents[i].name, ".tik") && ents[i].size >= 0x2C0) {
            uint8_t tik[0x2C0];
            if (read_at(fd, tik, sizeof tik, ents[i].off)) settings_add_titlekey(&tool->settings, tik + 0x2A0, tik + 0x180);
        }
    p->found = false;
    for (int i = 0; i < n; i++) {
        if (!ends_with(ents[i].name, ".nca") || ends_with(ents[i].name, ".cnmt.nca")) continue;
        nca_ctx_t c;
        nca_init(&c);
        c.tool_ctx = tool;
        c.file = window(fd, ents[i].off, ents[i].size);
        if (!c.file) continue;
        if (nca_decrypt_header(&c) && c.header.content_type == 0 /* program */ && (!p->found || ents[i].size > p->e.size)) {
            p->fd = fd, p->e = ents[i], p->title = c.header.title_id, p->found = true;
        }
        fclose(c.file);
    }
    if (!p->found) fprintf(stderr, "error: no game program in the %s (is it the base game / its update?)\n", what);
    return p->found;
}

// the forked worker: exit status 0 = done
static int worker(const char *keys, int base_fd, int upd_fd, const char *out) {
    static hactool_ctx_t tool, base_tool;
    memset(&tool, 0, sizeof tool);
    memset(&base_tool, 0, sizeof base_tool);
    pki_initialize_keyset(&tool.settings.keyset, KEYSET_RETAIL);
    FILE *kf = fopen(keys, "r");
    if (!kf) { fprintf(stderr, "error: prod.keys can't be read\n"); return 2; }
    extkeys_initialize_settings(&tool.settings, kf);
    fclose(kf);
    pki_derive_keys(&tool.settings.keyset);
    static const uint8_t zero[0x20];
    if (!memcmp(tool.settings.keyset.header_key, zero, sizeof zero)) {
        fprintf(stderr, "error: that prod.keys has no header_key: dump the keys from your Switch with Lockpick_RCM\n");
        return 3;
    }
    Program base, upd = {.found = false};
    if (!scan(base_fd, &tool, &base, "game")) return 4;
    if (upd_fd >= 0 && !scan(upd_fd, &tool, &upd, "update")) return 4;
    if (upd.found && (upd.title & ~0x800ull) != base.title) {
        fprintf(stderr, "error: the update (%016llx) isn't this game's (%016llx)\n", (unsigned long long)upd.title,
                (unsigned long long)base.title);
        return 5;
    }
    mkdir(out, 0775);
    char exefs[512], romfs[512];
    snprintf(exefs, sizeof exefs, "%s/exefs", out);
    snprintf(romfs, sizeof romfs, "%s/romfs.bin", out);
    mkdir(exefs, 0775);

    Program *tgt = upd.found ? &upd : &base;
    nca_ctx_t ctx;
    nca_init(&ctx);
    ctx.tool_ctx = &tool;
    ctx.is_cli_target = true;
    tool.file_type = FILETYPE_NCA;
    tool.action = ACTION_EXTRACT;
    tool.settings.exefs_dir_path.enabled = 1;
    filepath_set(&tool.settings.exefs_dir_path.path, exefs);
    tool.settings.romfs_path.enabled = 1;
    filepath_set(&tool.settings.romfs_path.path, romfs);
    if (upd.found) {  // the base game's RomFS under the update's patch (hactool --basenca)
        base_tool.file_type = FILETYPE_NCA;
        memcpy(&base_tool.settings.keyset, &tool.settings.keyset, sizeof(nca_keyset_t));
        base_tool.settings.known_titlekeys = tool.settings.known_titlekeys;
        nca_ctx_t *b = malloc(sizeof *b);
        nca_init(b);
        b->file = window(base.fd, base.e.off, base.e.size);
        b->tool_ctx = &base_tool;
        b->is_cli_target = false;
        base_tool.file = b->file;
        tool.base_file = b->file;
        tool.base_file_type = BASEFILE_NCA;
        tool.base_nca_ctx = b;
        nca_process(b);
    }
    shared->phase = 2;
    ctx.file = window(tgt->fd, tgt->e.off, tgt->e.size);
    tool.file = ctx.file;
    nca_process(&ctx);
    struct stat st;
    char main_path[600];
    snprintf(main_path, sizeof main_path, "%s/main", exefs);
    if (stat(main_path, &st) || stat(romfs, &st) || st.st_size < (1 << 20)) {
        fprintf(stderr, "error: the game's files didn't come out whole (are the keys from a Switch updated past the game's version?)\n");
        return 6;
    }
    return 0;
}

// ---- the launcher's side (runtime/ui/launcher.mm): blocking; progress through pla_gamefiles_progress
static volatile pid_t child;
void pla_gamefiles_progress(uint64_t *done, uint64_t *total, int *phase) {
    if (!shared) { *done = *total = 0, *phase = 0; return; }
    *done = shared->done, *total = shared->total, *phase = shared->phase;
}
void pla_gamefiles_cancel(void) {
    if (child > 0) kill(child, SIGKILL);
}

bool pla_gamefiles_import(const char *keys, int base_fd, int upd_fd, const char *out, char *err, size_t errn) {
    err[0] = 0;
    if (!shared) shared = mmap(NULL, sizeof *shared, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (shared == MAP_FAILED) { shared = NULL; snprintf(err, errn, "no memory"); return false; }
    memset((void *)shared, 0, sizeof *shared);
    int pipefd[2];
    if (pipe(pipefd)) { snprintf(err, errn, "pipe: %s", strerror(errno)); return false; }
    fflush(NULL);
    pid_t pid = fork();
    if (pid < 0) { snprintf(err, errn, "fork: %s", strerror(errno)); close(pipefd[0]), close(pipefd[1]); return false; }
    if (pid == 0) {  // (the copy: only plain C from here - no JNI, no SDL)
        close(pipefd[0]);
        dup2(pipefd[1], 2);
        int fdout = open("/dev/null", O_WRONLY);  // (hactool's info prints)
        if (fdout >= 0) dup2(fdout, 1);
        _exit(worker(keys, base_fd, upd_fd, out));
    }
    child = pid;
    close(pipefd[1]);
    // the worker's stderr: kept for the reason of a failure (its last "error:" line, else its last line)
    char buf[4096], last[512] = "", last_err[512] = "";
    size_t have = 0;
    for (;;) {
        ssize_t r = read(pipefd[0], buf + have, sizeof buf - 1 - have);
        if (r <= 0) break;
        have += (size_t)r;
        buf[have] = 0;
        char *nl;
        while ((nl = strchr(buf, '\n'))) {
            *nl = 0;
            if (buf[0]) {
                fprintf(stderr, "[gamefiles] %s\n", buf);
                snprintf(last, sizeof last, "%s", buf);
                if (!strncmp(buf, "error: ", 7)) snprintf(last_err, sizeof last_err, "%s", buf + 7);
            }
            have -= (size_t)(nl + 1 - buf);
            memmove(buf, nl + 1, have + 1);
        }
        if (have == sizeof buf - 1) have = 0;
    }
    close(pipefd[0]);
    int status = 0;
    waitpid(pid, &status, 0);
    child = 0;
    bool ok = WIFEXITED(status) && WEXITSTATUS(status) == 0;
    if (!ok) snprintf(err, errn, "%s", last_err[0] ? last_err : last[0] ? last : "the import stopped");
    return ok;
}
