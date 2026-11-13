// libam.so — the one shim library Apple's MusicKit native libraries see on HarmonyOS.
//
// Feasibility spike only: not part of the app or its build. Written for Yoin, modelled on the
// approach of github.com/6Jerries/AMFOrHarmonyOS_Base (MIT); no code copied from it.
//
// tools/patch_musickit_so.py rewrites the DT_NEEDED entries of
//   libappleMusicSDK.so : liblog.so, libOpenSLES.so, libandroid.so  -> libam.so
//   libc++_shared.so    : libdl.so                                  -> libam.so
// so everything below resolves through the normal DT_NEEDED chain (no RTLD_GLOBAL preloading,
// which HarmonyOS's per-app linker namespaces make unreliable).
//
// What the two Apple-shipped libraries import that the OHOS musl libc.so (API 26 sysroot) lacks,
// measured with llvm-nm against the sysroot (docs/applemusic.md, "Link-time gap"):
//   libappleMusicSDK.so : __android_log_print, __android_log_vprint, slCreateEngine,
//                         SL_IID_{ENGINE,PLAY,VOLUME,EQUALIZER,BASSBOOST,ANDROIDCONFIGURATION,
//                                 ANDROIDSIMPLEBUFFERQUEUE}
//   libc++_shared.so    : __errno, __sF, android_set_abort_message, strtoll_l, strtoull_l,
//                         __cxa_thread_atexit_impl
// Nothing else: every other import (pthread, stdio, string, math, dl*) exists in musl.

#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <pthread.h>

#define AM_EXPORT extern "C" __attribute__((visibility("default")))

namespace {

// ---------------------------------------------------------------- hilog (liblog replacement)

using OhLogPrint = int (*)(int type, int level, unsigned int domain, const char *tag, const char *fmt, ...);

OhLogPrint g_hilogPrint = nullptr;
pthread_once_t g_hilogOnce = PTHREAD_ONCE_INIT;

OhLogPrint HilogPrint() {
    // dlopen instead of linking hilog_ndk.z.so, so the shim has no OHOS-only link dependency;
    // pthread_once rather than a function-local static, which would need the C++ runtime's guards.
    pthread_once(&g_hilogOnce, [] {
        void *lib = dlopen("libhilog_ndk.z.so", RTLD_NOW | RTLD_LOCAL);
        g_hilogPrint = lib ? reinterpret_cast<OhLogPrint>(dlsym(lib, "OH_LOG_Print")) : nullptr;
    });
    return g_hilogPrint;
}

constexpr int kLogApp = 0;          // LOG_APP
constexpr unsigned kDomain = 0xA51; // Yoin spike domain
constexpr int kAndroidWarn = 5, kAndroidError = 6, kAndroidInfo = 4;

int HilogLevel(int androidPriority) {
    if (androidPriority >= kAndroidError) return 6; // LOG_ERROR
    if (androidPriority >= kAndroidWarn) return 5;  // LOG_WARN
    if (androidPriority >= kAndroidInfo) return 4;  // LOG_INFO
    return 3;                                       // LOG_DEBUG
}

int Forward(int priority, const char *tag, const char *fmt, va_list ap) {
    char line[1024];
    vsnprintf(line, sizeof line, fmt, ap);
    if (OhLogPrint print = HilogPrint()) {
        // %{private} by default: MusicKit logs ids and, on Android, once its developer token.
        print(kLogApp, HilogLevel(priority), kDomain, tag ? tag : "MusicKit", "%{private}s", line);
    }
    return 0;
}

} // namespace

AM_EXPORT int __android_log_print(int priority, const char *tag, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int r = Forward(priority, tag, fmt, ap);
    va_end(ap);
    return r;
}

AM_EXPORT int __android_log_vprint(int priority, const char *tag, const char *fmt, va_list ap) {
    return Forward(priority, tag, fmt, ap);
}

// ---------------------------------------------------------------- OpenSL ES (libOpenSLES replacement)
//
// HarmonyOS ships an OpenSL ES subset, but without SL_IID_ANDROIDSIMPLEBUFFERQUEUE /
// SL_IID_ANDROIDCONFIGURATION, and the SDK binds NOW: one missing IID fails the whole dlopen.
// The spike only needs FootHill + the decryptor, so the engine refuses to start; a real player
// renders through OHAudio (OH_AudioRenderer) instead of the SDK's SVFuseAudioRenderer.

struct AmSlInterfaceId {
    uint32_t time_low;
    uint16_t time_mid;
    uint16_t time_hi_and_version;
    uint16_t clock_seq;
    uint8_t node[6];
};
using AmSlInterfaceIdPtr = const AmSlInterfaceId *;

namespace {
// Distinct, non-null placeholders; the SDK only compares and passes them back to slCreateEngine.
const AmSlInterfaceId kIids[7] = {};
} // namespace

AM_EXPORT const AmSlInterfaceIdPtr SL_IID_ENGINE = &kIids[0];
AM_EXPORT const AmSlInterfaceIdPtr SL_IID_PLAY = &kIids[1];
AM_EXPORT const AmSlInterfaceIdPtr SL_IID_VOLUME = &kIids[2];
AM_EXPORT const AmSlInterfaceIdPtr SL_IID_EQUALIZER = &kIids[3];
AM_EXPORT const AmSlInterfaceIdPtr SL_IID_BASSBOOST = &kIids[4];
AM_EXPORT const AmSlInterfaceIdPtr SL_IID_ANDROIDCONFIGURATION = &kIids[5];
AM_EXPORT const AmSlInterfaceIdPtr SL_IID_ANDROIDSIMPLEBUFFERQUEUE = &kIids[6];

AM_EXPORT uint32_t slCreateEngine(void **engine, uint32_t, const void *, uint32_t, const AmSlInterfaceIdPtr *,
                                  const uint32_t *) {
    if (engine) *engine = nullptr;
    return 0x0000000D; // SL_RESULT_FEATURE_UNSUPPORTED
}

// ---------------------------------------------------------------- bionic-only libc (for NDK libc++_shared)

// bionic's errno accessor; musl's is __errno_location. Same thread-local, so errno stays coherent.
AM_EXPORT int *__errno() { return __errno_location(); }

// bionic's FILE array behind stdin/stdout/stderr. libc++ touches it only on its abort path
// (writing a message to stderr). Zeroed space keeps relocation happy; a write there would crash,
// which on that path is the abort it was about to do anyway. sizeof(bionic FILE) on arm64 is 152.
AM_EXPORT unsigned char __sF[3 * 152] __attribute__((aligned(16))) = {};

AM_EXPORT void android_set_abort_message(const char *message) {
    __android_log_print(7 /* FATAL */, "MusicKitAbort", "%s", message ? message : "");
}

AM_EXPORT long long strtoll_l(const char *s, char **end, int base, void *) { return strtoll(s, end, base); }

AM_EXPORT unsigned long long strtoull_l(const char *s, char **end, int base, void *) {
    return strtoull(s, end, base);
}

// thread_local destructors registered by NDK libc++. musl has no __cxa_thread_atexit_impl; run
// them from a pthread key destructor (per thread, last-registered first).
namespace {
struct AtExit {
    void (*fn)(void *);
    void *obj;
    AtExit *next;
};
pthread_key_t g_key;
pthread_once_t g_once = PTHREAD_ONCE_INIT;

void RunAtExit(void *head) {
    for (auto *e = static_cast<AtExit *>(head); e;) {
        AtExit *next = e->next;
        e->fn(e->obj);
        free(e);
        e = next;
    }
}
} // namespace

AM_EXPORT int __cxa_thread_atexit_impl(void (*fn)(void *), void *obj, void *) {
    pthread_once(&g_once, [] { pthread_key_create(&g_key, RunAtExit); });
    auto *e = static_cast<AtExit *>(malloc(sizeof(AtExit)));
    if (!e) return -1;
    *e = {fn, obj, static_cast<AtExit *>(pthread_getspecific(g_key))};
    pthread_setspecific(g_key, e);
    return 0;
}
