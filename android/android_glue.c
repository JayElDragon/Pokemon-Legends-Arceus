#define _GNU_SOURCE  // (cpu_set_t / CPU_SET: power saving below)
// Android entry: SDLActivity loads libSDL3.so and libmain.so and calls SDL_main with the arguments from
// PlaActivity.getArguments() (the data root, --play). The runtime's own main() does the rest.
#include <stdio.h>
#include <dlfcn.h>
#include <time.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdatomic.h>
#include <jni.h>
#include <SDL3/SDL.h>
#include "ui/menu.h"
#include "settings.h"

Uint32 pla_touch_event;

// Android's thermal headroom (PlaActivity, every 2 s; -1 unknown): runtime/nvn/output_scale.c's thermal governor
_Atomic float pla_thermal_headroom = -1;
JNIEXPORT void JNICALL Java_com_jay_plarecomp_PlaActivity_nativeThermal(JNIEnv *env, jclass cls, jfloat headroom) {
    atomic_store_explicit(&pla_thermal_headroom, headroom, memory_order_relaxed);
}

// The app off the screen (PlaActivity onPause / onResume): runtime/nvn/vulkan/device.c backend_present waits on it
volatile int pla_background;
// ---- crash recovery: a mark file in the data root while the game is on screen (PLAY to the background or a proper
// exit). Still there at the next start: the last session ended while playing - a GPU loss, a crash, Android killing
// it - and the start screen offers safe options (runtime/ui/launcher.mm). Swiping the app away happens after
// onPause, which removes the mark, so that doesn't count.
static volatile int pla_playing;
static void session_mark(int on) {
    if (on) { FILE *f = fopen("running.flag", "w"); if (f) fclose(f); }
    else remove("running.flag");
}
void pla_session_playing(void) { pla_playing = 1; session_mark(1); }
bool pla_session_crashed(void) {
    FILE *f = fopen("running.flag", "r");
    if (!f) return false;
    fclose(f);
    remove("running.flag");
    return true;
}

JNIEXPORT void JNICALL Java_com_jay_plarecomp_PlaActivity_nativeBackground(JNIEnv *env, jclass cls, jboolean background) {
    pla_background = background ? 1 : 0;
    if (pla_playing) session_mark(!background);
}

// ---- the graphics driver rows of the port menu (runtime/ui/menu.mm, Graphics tab) -> PlaActivity / DriverStore.java
// One call into the activity: kind 'Z' returns a boolean, 'V' nothing, 'B' takes a boolean, 'S' returns a string.
static jboolean activity_call(const char *name, char kind, jboolean arg, char *out, size_t out_n) {
    JNIEnv *env = SDL_GetAndroidJNIEnv();
    jobject activity = env ? SDL_GetAndroidActivity() : NULL;
    if (!activity) return JNI_FALSE;
    jboolean r = JNI_FALSE;
    jclass cls = (*env)->GetObjectClass(env, activity);
    const char *sig = kind == 'Z' ? "()Z" : kind == 'B' ? "(Z)V" : kind == 'S' ? "()Ljava/lang/String;" : "()V";
    jmethodID m = (*env)->GetMethodID(env, cls, name, sig);
    if (m) {
        if (kind == 'Z') r = (*env)->CallBooleanMethod(env, activity, m);
        else if (kind == 'B') (*env)->CallVoidMethod(env, activity, m, arg);
        else if (kind == 'S') {
            jstring js = (jstring)(*env)->CallObjectMethod(env, activity, m);
            const char *c = js ? (*env)->GetStringUTFChars(env, js, NULL) : NULL;
            if (out && out_n) snprintf(out, out_n, "%s", c ? c : "");
            if (c) (*env)->ReleaseStringUTFChars(env, js, c);
            if (js) (*env)->DeleteLocalRef(env, js);
        } else (*env)->CallVoidMethod(env, activity, m);
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
    return r;
}
bool pla_driver_installed(void) { return activity_call("driverInstalled", 'Z', 0, NULL, 0); }
bool pla_driver_turnip_chosen(void) { return activity_call("driverTurnipChosen", 'Z', 0, NULL, 0); }
void pla_driver_choose(bool turnip) { activity_call("driverChoose", 'B', turnip, NULL, 0); }
void pla_driver_import(void) { activity_call("driverImport", 'V', 0, NULL, 0); }
void pla_edit_touch_layout(void) { activity_call("editTouchLayout", 'V', 0, NULL, 0); }
void pla_driver_status(char *out, size_t n) { out[0] = 0; activity_call("driverStatus", 'S', 0, out, n); }
void pla_driver_name(char *out, size_t n) { out[0] = 0; activity_call("driverName", 'S', 0, out, n); }  // (the imported driver: "Turnip 25.1", ...)
void pla_restart_app(void) { activity_call("restartApp", 'V', 0, NULL, 0); }

// ---- Game Files (runtime/ui/launcher.mm -> PlaActivity / GameFiles.java): kind 0 prod.keys, 1 game, 2 update
// sig: "(I)V", "(I)I" (returns the int), "(I)Ljava/lang/String;" / "()Ljava/lang/String;" (into out)
static int activity_call_int(const char *name, const char *sig, int arg, char *out, size_t out_n) {
    JNIEnv *env = SDL_GetAndroidJNIEnv();
    jobject activity = env ? SDL_GetAndroidActivity() : NULL;
    if (out && out_n) out[0] = 0;
    if (!activity) return -1;
    int r = -1;
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID m = (*env)->GetMethodID(env, cls, name, sig);
    if (m) {
        if (!strcmp(sig, "(I)I")) r = (*env)->CallIntMethod(env, activity, m, (jint)arg);
        else if (!strcmp(sig, "(I)V")) (*env)->CallVoidMethod(env, activity, m, (jint)arg), r = 0;
        else {
            jstring js = !strcmp(sig, "()Ljava/lang/String;") ? (jstring)(*env)->CallObjectMethod(env, activity, m)
                                                               : (jstring)(*env)->CallObjectMethod(env, activity, m, (jint)arg);
            const char *c = js ? (*env)->GetStringUTFChars(env, js, NULL) : NULL;
            if (out && out_n) snprintf(out, out_n, "%s", c ? c : "");
            if (c) (*env)->ReleaseStringUTFChars(env, js, c);
            if (js) (*env)->DeleteLocalRef(env, js);
            r = 0;
        }
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env), r = -1;
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
    return r;
}
void pla_gamefile_pick(int kind) { activity_call_int("gameFilePick", "(I)V", kind, NULL, 0); }
void pla_gamefile_label(int kind, char *out, size_t n) { activity_call_int("gameFileLabel", "(I)Ljava/lang/String;", kind, out, n); }
int pla_gamefile_open(int kind) { return activity_call_int("gameFileOpen", "(I)I", kind, NULL, 0); }
void pla_gamefile_keys_path(char *out, size_t n) { activity_call_int("gameFileKeysPath", "()Ljava/lang/String;", 0, out, n); }
void pla_gamefiles_done(bool forget) { activity_call("gameFilesDone", 'B', forget, NULL, 0); }

// ---- Saves tab (runtime/ui/launcher.mm): Android's file picker and zip code (SaveTransfer.java)
void pla_save_export(void) { activity_call("saveExport", 'V', 0, NULL, 0); }
void pla_logs_export(void) { activity_call("logsExport", 'V', 0, NULL, 0); }  // (the port menu: SaveTransfer.pickLogs)
void pla_save_import(void) { activity_call("saveImport", 'V', 0, NULL, 0); }
void launcher_save_result(int code, const char *detail);  // runtime/ui/launcher.mm
void launcher_save_picked(const char *dir);
JNIEXPORT void JNICALL Java_com_jay_plarecomp_SaveTransfer_nativeSaveResult(JNIEnv *env, jclass cls, jint code, jstring detail) {
    const char *d = detail ? (*env)->GetStringUTFChars(env, detail, NULL) : NULL;
    launcher_save_result(code, d ? d : "");
    if (d) (*env)->ReleaseStringUTFChars(env, detail, d);
}
JNIEXPORT void JNICALL Java_com_jay_plarecomp_SaveTransfer_nativeSavePicked(JNIEnv *env, jclass cls, jstring dir) {
    const char *d = dir ? (*env)->GetStringUTFChars(env, dir, NULL) : NULL;
    if (d) launcher_save_picked(d), (*env)->ReleaseStringUTFChars(env, dir, d);
}
// The driver this process runs on (PlaActivity set PLA_VULKAN_DRIVER at start for Turnip)
bool pla_gpu_is_adreno(void);  // android/vulkan_driver.c
bool pla_driver_turnip_active(void) { return getenv("PLA_VULKAN_DRIVER") != NULL && pla_gpu_is_adreno(); }

JNIEXPORT void JNICALL Java_com_jay_plarecomp_PlaActivity_touchState(
    JNIEnv *env, jclass cls, jint buttons, jint lx, jint ly, jint rx, jint ry) {
    if (!pla_touch_event) return;
    SDL_Event event = {0};
    event.type = pla_touch_event;
    event.user.code = buttons;
    event.user.data1 = (void *)(uintptr_t)((uint32_t)(uint16_t)lx | ((uint32_t)(uint16_t)ly << 16));
    event.user.data2 = (void *)(uintptr_t)((uint32_t)(uint16_t)rx | ((uint32_t)(uint16_t)ry << 16));
    SDL_PushEvent(&event);
}

JNIEXPORT jboolean JNICALL Java_com_jay_plarecomp_PlaActivity_touchInGame(JNIEnv *env, jclass cls) {
    extern int g_start_screen;
    extern int pla_touch_game_active;
    return __atomic_load_n(&pla_touch_game_active, __ATOMIC_RELAXED) && !g_start_screen && !menu_is_open();
}

// On-screen controls on / off: the port menu's Video tab (g_settings.touch_controls), asked by TouchControls.java
JNIEXPORT jboolean JNICALL Java_com_jay_plarecomp_PlaActivity_touchEnabled(JNIEnv *env, jclass cls) {
    return g_settings.touch_controls;
}

// The display mode PlaActivity asks for: 120 Hz only for the 120 fps mode (frame generation), 60 Hz otherwise
JNIEXPORT jfloat JNICALL Java_com_jay_plarecomp_PlaActivity_nativeRefreshRate(JNIEnv *env, jclass cls) {
    return g_settings.framegen ? 120.0f : 60.0f;
}

// ---- power saving (g_settings.power_save, the port menu's Video tab)
// Measured on the Odin 3: in the open world no game thread is above ~18% of a core, but the scheduler still put them on
// the two prime cores (cpu6-7) at 4.09 GHz - those ran at 76-80 C with the fans up while the others sat near 60 C.
// Power saving keeps every thread of the process on the other cores (the slower clusters), which have room for the
// game at 30 fps. Threads made later inherit the mask from the thread that makes them; each thread that exists now
// gets it too (/proc/self/task). Off: all cores again.
#include <dirent.h>
#include <errno.h>
#include <sched.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static long cpu_max_khz(int cpu) {
    char p[96];
    snprintf(p, sizeof p, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE *f = fopen(p, "r");
    long v = 0;
    if (f) { if (fscanf(f, "%ld", &v) != 1) v = 0; fclose(f); }
    return v;
}

void pla_apply_power(void) {
    int n = (int)sysconf(_SC_NPROCESSORS_CONF);
    if (n <= 1 || n > CPU_SETSIZE) return;
    long top = 0, khz[CPU_SETSIZE];
    for (int i = 0; i < n; i++) if ((khz[i] = cpu_max_khz(i)) > top) top = khz[i];
    cpu_set_t set;
    CPU_ZERO(&set);
    int kept = 0;
    for (int i = 0; i < n; i++)
        if (!g_settings.power_save || !top || khz[i] < top) { CPU_SET(i, &set); kept++; }
    // CPU cores (port menu): the last N of the slower cores only (cpu0 takes most interrupts: left out first)
    int want = g_settings.cpu_cores;
    if (want > 0 && want < kept) {
        cpu_set_t few;
        CPU_ZERO(&few);
        int got = 0;
        for (int i = n - 1; i >= 0 && got < want; i--)
            if (CPU_ISSET(i, &set) && (!top || khz[i] < top)) { CPU_SET(i, &few); got++; }
        if (got == want) set = few, kept = got;
    }
    if (!kept)  // (one cluster only: nothing to leave out)
        for (int i = 0; i < n; i++) { CPU_SET(i, &set); kept++; }
    int done = 0;
    DIR *d = opendir("/proc/self/task");
    for (struct dirent *de; d && (de = readdir(d));) {
        int tid = atoi(de->d_name);
        if (tid > 0 && syscall(__NR_sched_setaffinity, tid, sizeof set, &set) == 0) done++;
    }
    if (d) closedir(d);
    SDL_Log("[power] %s: %d of %d cores (prime %ld MHz %s), %d threads", g_settings.power_save ? "saving" : "all cores",
            kept, n, top / 1000, g_settings.power_save ? "left out" : "used", done);
    fprintf(stderr, "[power] %s: %d of %d cores, %d threads\n", g_settings.power_save ? "power saving" : "all cores", kept, n, done);
}

// ---- ADPF (Android's performance hint) with power saving
// A ceiling on the cores' clocks needs privileges an app doesn't have (uclamp: "Operation not permitted" on the Odin 3).
// What an app may do is tell the system how long each frame's work really takes against its target (runtime/nvn/pace.c:
// the game thread's or the render thread's CPU time, once a frame).
// The session holds two threads only: the one presenting (the game's frame thread) and the render thread. With every
// thread of the process in it (64), Qualcomm's scheduler (WALT) groups the session's threads and sizes the clock by
// their load summed: the job workers' ~170% of a core read as one thread over its limit, and all six cores sat at
// 3.53 GHz while no thread used more than ~37% (Odin 3, Jubilife, measured). The workers now get the ordinary
// per-thread clock choice.
#include <android/performance_hint.h>
_Atomic int pla_render_tid;  // runtime/nvn/rqueue.c's render thread, once it runs
static APerformanceHintSession *adpf;
static int adpf_render;
static int64_t adpf_target;

// Android 15's work durations with a GPU part (looked up: the app's minimum is Android 13). The GPU's own time a frame
// goes to the system too: with the CPU time alone the GPU's governor saw 79% busy at its 660 MHz floor and never
// stepped up - 46-51 fps, the GPU the frame's limit.
typedef struct AWorkDuration AWorkDuration;
static struct {
    bool tried, ok;
    AWorkDuration *(*create)(void);
    void (*start)(AWorkDuration *, int64_t);
    void (*total)(AWorkDuration *, int64_t);
    void (*cpu)(AWorkDuration *, int64_t);
    void (*gpu)(AWorkDuration *, int64_t);
    int (*report)(APerformanceHintSession *, AWorkDuration *);
    AWorkDuration *wd;
} wd2;
static void adpf_report(uint64_t busy_ns, uint64_t gpu_ns, uint64_t now_ns) {
    if (!wd2.tried) {
        wd2.tried = true;
        wd2.create = (AWorkDuration *(*)(void))dlsym(RTLD_DEFAULT, "AWorkDuration_create");
        wd2.start = (void (*)(AWorkDuration *, int64_t))dlsym(RTLD_DEFAULT, "AWorkDuration_setWorkPeriodStartTimestampNanos");
        wd2.total = (void (*)(AWorkDuration *, int64_t))dlsym(RTLD_DEFAULT, "AWorkDuration_setActualTotalDurationNanos");
        wd2.cpu = (void (*)(AWorkDuration *, int64_t))dlsym(RTLD_DEFAULT, "AWorkDuration_setActualCpuDurationNanos");
        wd2.gpu = (void (*)(AWorkDuration *, int64_t))dlsym(RTLD_DEFAULT, "AWorkDuration_setActualGpuDurationNanos");
        wd2.report = (int (*)(APerformanceHintSession *, AWorkDuration *))dlsym(RTLD_DEFAULT, "APerformanceHint_reportActualWorkDuration2");
        wd2.ok = wd2.create && wd2.start && wd2.total && wd2.cpu && wd2.gpu && wd2.report && (wd2.wd = wd2.create());
        fprintf(stderr, "[power] GPU work durations to the performance hint: %s\n", wd2.ok ? "yes" : "not on this Android");
    }
    if (wd2.ok && gpu_ns) {
        uint64_t total = busy_ns > gpu_ns ? busy_ns : gpu_ns;
        wd2.start(wd2.wd, (int64_t)(now_ns > total ? now_ns - total : 1));
        wd2.total(wd2.wd, (int64_t)total);
        wd2.cpu(wd2.wd, (int64_t)(busy_ns ? busy_ns : 1));
        wd2.gpu(wd2.wd, (int64_t)gpu_ns);
        int r = wd2.report(adpf, wd2.wd);
        static int nlog;
        if (r && nlog++ < 3) fprintf(stderr, "[power] GPU work duration refused (%d)\n", r);
        if (!r) return;
    }
    if (busy_ns > 0) APerformanceHint_reportActualWorkDuration(adpf, (int64_t)busy_ns);
}

void pla_adpf_frame2(uint64_t busy_ns, uint64_t gpu_ns, uint64_t target_ns);
void pla_adpf_frame(uint64_t busy_ns, uint64_t target_ns) { pla_adpf_frame2(busy_ns, 0, target_ns); }
void pla_adpf_frame2(uint64_t busy_ns, uint64_t gpu_ns, uint64_t target_ns) {
    static int failed;
    // (on whatever the power saving setting: the session is how the system learns the frame's CPU and GPU time - with
    // power saving off nothing reached it, and the GPU sat at its floor)
    if (failed > 3) {
        if (adpf) APerformanceHint_closeSession(adpf), adpf = NULL;
        return;
    }
    int render = atomic_load(&pla_render_tid);
    if (!adpf || render != adpf_render) {
        int32_t tids[2];
        int n = 0;
        tids[n++] = (int32_t)gettid();
        if (render > 0 && render != tids[0]) tids[n++] = render;
        APerformanceHintManager *m = APerformanceHint_getManager();
        if (adpf) APerformanceHint_closeSession(adpf), adpf = NULL;
        adpf = m ? APerformanceHint_createSession(m, tids, (size_t)n, (int64_t)target_ns) : NULL;
        adpf_render = render;
        if (!adpf) { failed++; fprintf(stderr, "[power] performance hint session refused\n"); return; }
        adpf_target = (int64_t)target_ns;
        fprintf(stderr, "[power] performance hint session: frame thread %d, render thread %d, target %.1f ms\n", tids[0],
                render, target_ns / 1e6);
        // (no "prefer power efficiency": with it the GPU stayed at 660 MHz of 1.1 GHz at 80% busy, 46-49 fps - the CPU
        // side's heat was the frequency floor and the summed thread load, both fixed above)
    }
    if ((int64_t)target_ns != adpf_target) APerformanceHint_updateTargetWorkDuration(adpf, (int64_t)target_ns), adpf_target = (int64_t)target_ns;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    adpf_report(busy_ns, gpu_ns, (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec);
}

int main(int argc, char **argv);  // runtime/main.c

__attribute__((visibility("default"))) int SDL_main(int argc, char **argv) { return main(argc, argv); }
