// libmusickit_probe_napi.so — the same probe as musickit_probe, run inside an app process.
//
// Why: HarmonyOS refuses to exec native files from /data/local/tmp from `hdc shell`, even a copy
// of the system's own toybox (docs/applemusic.md, "Phase 0 results (device)"). The app process
// is also the namespace the real player would run in, so this is the check that counts anyway.
//
// Use (personal test HAP only, signed with the owner's debug certificate; never in Yoin itself):
//   1. Put libmusickit_probe_napi.so, libam.so, libc++_ndk1.so and libappleMusicSDK.so flat in the
//      test module's libs/arm64-v8a/ (the HAP's native lib root; subdirectories are not searched).
//   2. ArkTS: import probe from 'libmusickit_probe_napi.so';
//             const report: string = probe.run('--canary --cxx --call-instance --decryptor');
//   3. Every line also goes to hilog (domain 0xA51, tag MusicKitProbe), so if Apple's code aborts
//      the process the lines before the crash survive: `hdc shell hilog -x | grep MusicKitProbe`.
//
// Feasibility spike only (docs/applemusic.md); not part of the app or its build.

#include <napi/native_api.h>

#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <pthread.h>
#include <string>
#include <sys/types.h>
#include <vector>

extern FILE *g_out;
int RunMusicKitProbe(int argc, const char *const *argv);

namespace {

using OhLogPrint = int (*)(int type, int level, unsigned int domain, const char *tag, const char *fmt, ...);

struct Sink {
    std::string all;  // returned to ArkTS
    std::string line; // pending partial line for hilog
    OhLogPrint log = nullptr;
};

ssize_t SinkWrite(void *cookie, const char *data, size_t size) {
    auto *sink = static_cast<Sink *>(cookie);
    sink->all.append(data, size);
    for (size_t i = 0; i < size; ++i) {
        if (data[i] != '\n') {
            sink->line.push_back(data[i]);
            continue;
        }
        // %{public}: the probe prints addresses and symbol names only, never tokens or user data.
        if (sink->log) sink->log(0 /* LOG_APP */, 4 /* INFO */, 0xA51, "MusicKitProbe", "%{public}s", sink->line.c_str());
        sink->line.clear();
    }
    return static_cast<ssize_t>(size);
}

pthread_mutex_t g_runLock = PTHREAD_MUTEX_INITIALIZER;

// run(flags: string): string — flags as on the command line, e.g. "--canary --cxx".
napi_value Run(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    std::string flags;
    if (argc >= 1) {
        size_t length = 0;
        napi_get_value_string_utf8(env, args[0], nullptr, 0, &length);
        flags.resize(length + 1);
        napi_get_value_string_utf8(env, args[0], flags.data(), flags.size(), &length);
        flags.resize(length);
    }

    // argv: program name, "" (= the app's own lib namespace), then the flags split on spaces.
    std::vector<std::string> words{"musickit_probe", ""};
    for (size_t start = 0; start < flags.size();) {
        size_t end = flags.find(' ', start);
        if (end == std::string::npos) end = flags.size();
        if (end > start) words.push_back(flags.substr(start, end - start));
        start = end + 1;
    }
    std::vector<const char *> argv;
    for (const std::string &w : words) argv.push_back(w.c_str());

    Sink sink;
    if (void *hilog = dlopen("libhilog_ndk.z.so", RTLD_NOW | RTLD_LOCAL))
        sink.log = reinterpret_cast<OhLogPrint>(dlsym(hilog, "OH_LOG_Print"));

    pthread_mutex_lock(&g_runLock);
    cookie_io_functions_t io{};
    io.write = SinkWrite;
    FILE *stream = fopencookie(&sink, "w", io);
    FILE *previous = g_out;
    g_out = stream ? stream : previous;
    int code = RunMusicKitProbe(static_cast<int>(argv.size()), argv.data());
    std::fprintf(g_out, "exit code %d\n", code);
    g_out = previous;
    if (stream) std::fclose(stream);
    pthread_mutex_unlock(&g_runLock);

    napi_value result = nullptr;
    napi_create_string_utf8(env, sink.all.c_str(), sink.all.size(), &result);
    return result;
}

napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
        {"run", nullptr, Run, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof desc / sizeof desc[0], desc);
    return exports;
}

napi_module g_module = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "musickit_probe_napi",
    .nm_priv = nullptr,
    .reserved = {0},
};

} // namespace

extern "C" __attribute__((constructor)) void RegisterMusicKitProbeModule() { napi_module_register(&g_module); }
