// musickit_probe — can HarmonyOS load Apple's Android MusicKit native library and reach the
// pieces a player needs? Feasibility spike only (docs/applemusic.md); not part of the app.
//
// Usage on a device (the owner's personal build; nothing here runs in CI):
//   musickit_probe <dir> [--call-instance] [--canary]
// where <dir> holds, flat (HarmonyOS app namespaces don't search subdirectories):
//   libam.so              this spike's shim (shim/libam_shim.cpp)
//   libc++_ndk1.so        the AAR's libc++_shared.so after tools/patch_musickit_so.py
//   libappleMusicSDK.so   the AAR's libappleMusicSDK.so after tools/patch_musickit_so.py
//
// It never ships Apple binaries: the owner extracts and patches them locally.
//
// What it checks, in order:
//   1. dlopen of the patched pair resolves every import (BIND_NOW: one missing symbol fails it).
//   2. The C++ entry points behind the Java layer resolve by their mangled names: FootHill
//      (FairPlay session: SPC out, CKC in), the decryptor factory and Pastis sample decryption.
//      Calling them directly needs no JNIEnv: the JNI exports are thin JavaCPP wrappers.
//   3. --canary: bionic code reads its stack-protector canary from TPIDR_EL0+40 (494 functions in
//      libappleMusicSDK.so). Under musl that word is whatever sits there in static TLS; it must
//      stay constant for the lifetime of every frame, or __stack_chk_fail aborts. Prints it on
//      several threads, twice, so a device run shows whether it is stable.
//   4. --call-instance: calls SVFootHillSessionCtrl::instance(), the first real native call
//      MusicKit makes (no network, no token). Off by default.

#include <cinttypes>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <pthread.h>
#include <string>

namespace {

struct Symbol {
    const char *what;
    const char *mangled;
};

// From `llvm-nm -D --defined-only libappleMusicSDK.so` (mediaplayback 1.1.1, arm64-v8a).
constexpr Symbol kSymbols[] = {
    {"SVFootHillSessionCtrl::instance()", "_ZN21SVFootHillSessionCtrl8instanceEv"},
    {"SVFootHillSessionCtrl::generateSessionExchange(string const&, shared_ptr<SVData> const&, string const&, string const&)",
     "_ZN21SVFootHillSessionCtrl23generateSessionExchangeERKNSt6__ndk112basic_stringIcNS0_11char_"
     "traitsIcEENS0_9allocatorIcEEEERKNS0_10shared_ptrI6SVDataEES8_S8_"},
    {"SVFootHillSessionCtrl::generateSessionContext(string const&, string const&)",
     "_ZN21SVFootHillSessionCtrl22generateSessionContextERKNSt6__ndk112basic_stringIcNS0_11char_"
     "traitsIcEENS0_9allocatorIcEEEES8_"},
    {"SVFootHillSessionCtrl::decryptContext(string const&)",
     "_ZN21SVFootHillSessionCtrl14decryptContextERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_"
     "9allocatorIcEEEE"},
    {"SVFootHillSessionCtrl::resetSessionContext(string const&)",
     "_ZN21SVFootHillSessionCtrl19resetSessionContextERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_"
     "9allocatorIcEEEE"},
    {"SVFootHillPContext::kdContext() const", "_ZNK18SVFootHillPContext9kdContextEv"},
    {"FootHillConfig::config(string const&)",
     "_ZN14FootHillConfig6configERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE"},
    {"SVData::SVData(void const*, unsigned long, bool)", "_ZN6SVDataC1EPKvmb"},
    {"SVData::bytes() const", "_ZNK6SVData5bytesEv"},
    {"SVData::length() const", "_ZNK6SVData6lengthEv"},
    {"SVDecryptorFactory::create(type, uchar const*, uint const&, uchar const*, uint const&, track type, bool)",
     "_ZN18SVDecryptorFactory6createERKN11SVDecryptor15SVDecryptorTypeEPKhRKjS5_S7_RKNS0_"
     "20SVDecryptorTrackTypeERKb"},
    {"SVPastisDecryptor::decryptSample(uchar const*, uint const&, uint*)",
     "_ZN17SVPastisDecryptor13decryptSampleEPKhRKjPj"},
    // The SDK's own decoder + OpenSL renderer: resolvable, but a HarmonyOS player would not use them.
    {"SVAudioDecoderJNI::_discardDecryptor()", "_ZN17SVAudioDecoderJNI17_discardDecryptorEv"},
};

void *Open(const std::string &dir, const char *name) {
    std::string path = dir + "/" + name;
    void *handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    std::printf("dlopen %-22s %s\n", name, handle ? "ok" : dlerror());
    return handle;
}

#if defined(__aarch64__)
uint64_t BionicCanarySlot() {
    uint64_t tp;
    __asm__ volatile("mrs %0, tpidr_el0" : "=r"(tp));
    return *reinterpret_cast<volatile uint64_t *>(tp + 40);
}
#else
uint64_t BionicCanarySlot() { return 0; }
#endif

void *PrintCanary(void *label) {
    uint64_t first = BionicCanarySlot();
    // Some libc work in between: anything that writes that TLS word would show up as a change.
    char buffer[64];
    std::snprintf(buffer, sizeof buffer, "%s", static_cast<const char *>(label));
    uint64_t second = BionicCanarySlot();
    std::printf("canary[%s] %016" PRIx64 " %016" PRIx64 " %s\n", buffer, first, second,
                first == second ? "stable" : "CHANGED");
    return nullptr;
}

} // namespace

int main(int argc, char **argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <dir> [--call-instance] [--canary]\n", argv[0]);
        return 2;
    }
    const std::string dir = argv[1];
    bool callInstance = false, canary = false;
    for (int i = 2; i < argc; ++i) {
        callInstance |= std::strcmp(argv[i], "--call-instance") == 0;
        canary |= std::strcmp(argv[i], "--canary") == 0;
    }

    if (canary) {
        PrintCanary(const_cast<char *>("main"));
        pthread_t threads[3];
        const char *labels[3] = {"t1", "t2", "t3"};
        for (int i = 0; i < 3; ++i) pthread_create(&threads[i], nullptr, PrintCanary, const_cast<char *>(labels[i]));
        for (pthread_t t : threads) pthread_join(t, nullptr);
    }

    // The shim and libc++ first, so a failure names the layer that broke.
    if (!Open(dir, "libam.so") || !Open(dir, "libc++_ndk1.so")) return 1;
    void *sdk = Open(dir, "libappleMusicSDK.so");
    if (!sdk) return 1;

    int missing = 0;
    for (const Symbol &s : kSymbols) {
        void *address = dlsym(sdk, s.mangled);
        std::printf("%-4s %s\n", address ? "ok" : "MISS", s.what);
        missing += address ? 0 : 1;
    }

    if (callInstance && missing == 0) {
        using Instance = void *(*)();
        auto instance = reinterpret_cast<Instance>(dlsym(sdk, kSymbols[0].mangled));
        void *ctrl = instance();
        std::printf("SVFootHillSessionCtrl::instance() = %p\n", ctrl);
    }
    std::printf("%s: %d of %zu symbols missing\n", missing ? "FAIL" : "PASS", missing,
                sizeof kSymbols / sizeof kSymbols[0]);
    return missing ? 1 : 0;
}
