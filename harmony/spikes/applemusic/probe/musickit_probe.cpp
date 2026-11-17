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
//   5. --cxx: which library each C++ ABI import of the Apple pair is bound to (read from their
//      GOTs), then two throws: one from NDK libc++ caught here (OHOS libc++abi), and a throw from
//      inside Apple's code through its own frames (see --decryptor).
//   6. --decryptor: SVDecryptorFactory::create with a dummy key URI. With no FootHill session the
//      Pastis constructor formats a message with an NDK ostringstream (ios_base::init, locale) and
//      throws SVError through the factory's cleanup frames. Purely local: no network, no DRM state.
//      Also type 0, which only logs through the shim's __android_log_print.

#include <cerrno>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <elf.h>
#include <exception>
#include <fcntl.h>
#include <link.h>
#include <new>
#include <pthread.h>
#include <string>
#include <unistd.h>

// Where every line goes: stdout for the executable, a hilog-backed stream inside a NAPI module
// (napi_probe.cpp), so a crash halfway still leaves the lines before it in the log.
FILE *g_out = stdout;

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
    // Empty dir: bare name, i.e. the app's own libs/arm64-v8a namespace (NAPI module case).
    std::string path = dir.empty() ? std::string(name) : dir + "/" + name;
    void *handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    std::fprintf(g_out, "dlopen %-22s %s\n", name, handle ? "ok" : dlerror());
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
    std::fprintf(g_out, "canary[%s] %016" PRIx64 " %016" PRIx64 " %s\n", buffer, first, second,
                first == second ? "stable" : "CHANGED");
    return nullptr;
}

// Which loaded module's static TLS block holds the word bionic code reads as its canary.
void WhereIsCanarySlot() {
#if defined(__aarch64__)
    uint64_t tp;
    __asm__ volatile("mrs %0, tpidr_el0" : "=r"(tp));
    const uintptr_t slot = tp + 40;
    std::fprintf(g_out, "tp=%#" PRIx64 " slot=tp+40\n", tp);
    dl_iterate_phdr(
        [](dl_phdr_info *info, size_t, void *data) {
            const uintptr_t slot = *static_cast<uintptr_t *>(data);
            for (int i = 0; i < info->dlpi_phnum; ++i) {
                const ElfW(Phdr) &ph = info->dlpi_phdr[i];
                if (ph.p_type != PT_TLS) continue;
                auto start = reinterpret_cast<uintptr_t>(info->dlpi_tls_data);
                bool hit = start && slot >= start && slot < start + ph.p_memsz;
                std::fprintf(g_out, "  TLS module %zu %-28s data=%#" PRIxPTR " memsz=%#" PRIx64 "%s\n", info->dlpi_tls_modid,
                            info->dlpi_name && *info->dlpi_name ? info->dlpi_name : "(main)", start,
                            static_cast<uint64_t>(ph.p_memsz), hit ? "  <-- holds tp+40" : "");
            }
            return 0;
        },
        const_cast<uintptr_t *>(&slot));
#endif
}

// Libc work that sets errno, allocates, formats and locks: if any of it writes the slot, it isn't
// a canary. Run on the calling thread.
bool CanaryStressStable() {
    const uint64_t before = BionicCanarySlot();
    for (int i = 0; i < 2000; ++i) {
        int fd = open("/nonexistent/yoin", O_RDONLY);
        if (fd >= 0) close(fd);
        errno = i;
        void *p = std::malloc(static_cast<size_t>(i) * 37 + 1);
        char buf[64];
        std::snprintf(buf, sizeof buf, "%d %f", i, i * 0.5);
        std::free(p);
        if (BionicCanarySlot() != before) return false;
    }
    return true;
}

// ---------------------------------------------------------------- --cxx: who serves the C++ ABI

struct Binding {
    const char *name;
};
constexpr Binding kAbi[] = {
    {"__cxa_throw"}, {"__cxa_allocate_exception"}, {"__cxa_begin_catch"}, {"__gxx_personality_v0"},
    {"_Unwind_Resume"}, {"_Znwm"}, {"_ZdlPv"}, {"_Znam"}, {"_ZTISt9exception"}, {"_ZTISt13runtime_error"},
    {"__cxa_guard_acquire"}, {"__dynamic_cast"},
};

struct GotQuery {
    const char *module; // basename to match
};

int ReportBindings(dl_phdr_info *info, size_t, void *data) {
    const char *want = static_cast<GotQuery *>(data)->module;
    const char *name = info->dlpi_name ? info->dlpi_name : "";
    const char *base = std::strrchr(name, '/');
    base = base ? base + 1 : name;
    if (std::strcmp(base, want) != 0) return 0;
    const ElfW(Addr) load = info->dlpi_addr;
    const ElfW(Dyn) *dyn = nullptr;
    for (int i = 0; i < info->dlpi_phnum; ++i)
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) dyn = reinterpret_cast<const ElfW(Dyn) *>(load + info->dlpi_phdr[i].p_vaddr);
    if (!dyn) return 1;
    auto fix = [load](ElfW(Addr) v) { return v < load ? v + load : v; }; // musl leaves d_ptr unrelocated
    const ElfW(Sym) *symtab = nullptr;
    const char *strtab = nullptr;
    const ElfW(Rela) *rela = nullptr, *jmprel = nullptr;
    size_t relasz = 0, pltsz = 0;
    for (const ElfW(Dyn) *d = dyn; d->d_tag != DT_NULL; ++d) {
        switch (d->d_tag) {
        case DT_SYMTAB: symtab = reinterpret_cast<const ElfW(Sym) *>(fix(d->d_un.d_ptr)); break;
        case DT_STRTAB: strtab = reinterpret_cast<const char *>(fix(d->d_un.d_ptr)); break;
        case DT_RELA: rela = reinterpret_cast<const ElfW(Rela) *>(fix(d->d_un.d_ptr)); break;
        case DT_RELASZ: relasz = d->d_un.d_val; break;
        case DT_JMPREL: jmprel = reinterpret_cast<const ElfW(Rela) *>(fix(d->d_un.d_ptr)); break;
        case DT_PLTRELSZ: pltsz = d->d_un.d_val; break;
        default: break;
        }
    }
    std::fprintf(g_out, "[%s] imports bound to:\n", want);
    for (const Binding &b : kAbi) {
        const char *where = "(not imported)";
        auto scan = [&](const ElfW(Rela) *r, size_t bytes) {
            for (size_t i = 0; r && i < bytes / sizeof(ElfW(Rela)); ++i) {
                const ElfW(Sym) &sym = symtab[ELF64_R_SYM(r[i].r_info)];
                if (ELF64_R_SYM(r[i].r_info) == 0 || sym.st_shndx != SHN_UNDEF) continue;
                if (std::strcmp(strtab + sym.st_name, b.name) != 0) continue;
                void *target = *reinterpret_cast<void **>(load + r[i].r_offset);
                Dl_info di{};
                where = dladdr(target, &di) && di.dli_fname ? di.dli_fname : "(unknown)";
                return true;
            }
            return false;
        };
        if (!scan(jmprel, pltsz)) scan(rela, relasz);
        std::fprintf(g_out, "  %-26s -> %s\n", b.name, where);
    }
    return 1;
}

// std::__ndk1::stoi(string const&, size_t*, int): throws std::invalid_argument from NDK libc++.
void ThrowFromNdkLibcxx(void *libcxx) {
    using Stoi = int (*)(const void *, size_t *, int);
    auto stoi = reinterpret_cast<Stoi>(
        dlsym(libcxx, "_ZNSt6__ndk14stoiERKNS_12basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEEPmi"));
    if (!stoi) {
        std::fprintf(g_out, "ndk stoi: not found\n");
        return;
    }
    // libc++ short-string layout (arm64, not the alternate layout): size<<1 in byte 0, then chars.
    alignas(8) unsigned char str[24] = {};
    str[0] = 3 << 1;
    std::memcpy(str + 1, "abc", 4);
    try {
        int v = stoi(str, nullptr, 10);
        std::fprintf(g_out, "ndk stoi: returned %d (expected a throw)\n", v);
    } catch (const std::exception &e) {
        std::fprintf(g_out, "ndk stoi: caught std::exception here: \"%s\"\n", e.what());
    } catch (...) {
        std::fprintf(g_out, "ndk stoi: caught (...) here, typeinfo did not match std::exception\n");
    }
}

// shared_ptr<T> is non-trivial, so it comes back through x8 like the SDK expects.
struct SharedPtrRet {
    void *ptr = nullptr;
    void *ctrl = nullptr;
    ~SharedPtrRet() {} // leaked on purpose: the probe exits right after
};

void TryDecryptor(void *sdk) {
    using Create = SharedPtrRet (*)(const int &, const unsigned char *, const unsigned &, const unsigned char *,
                                    const unsigned &, const int &, const bool &);
    auto create = reinterpret_cast<Create>(dlsym(
        sdk, "_ZN18SVDecryptorFactory6createERKN11SVDecryptor15SVDecryptorTypeEPKhRKjS5_S7_RKNS0_"
             "20SVDecryptorTrackTypeERKb"));
    if (!create) return;
    static const unsigned char key[] = "skd://yoin-probe/no-session";
    static const unsigned char iv[16] = {};
    const unsigned keyLen = sizeof key - 1, ivLen = sizeof iv;
    const int track = 0;
    const bool flag = false;
    for (int type : {0, 7}) {
        std::fprintf(g_out, "SVDecryptorFactory::create(type=%d) ...\n", type);
        std::fflush(g_out);
        try {
            SharedPtrRet r = create(type, key, keyLen, iv, ivLen, track, flag);
            std::fprintf(g_out, "  returned ptr=%p ctrl=%p\n", r.ptr, r.ctrl);
        } catch (const std::exception &e) {
            std::fprintf(g_out, "  threw through Apple frames, caught here as std::exception: \"%s\"\n", e.what());
        } catch (...) {
            std::fprintf(g_out, "  threw through Apple frames, caught here as (...)\n");
        }
        std::fprintf(g_out, "  canary after: %016" PRIx64 "\n", BionicCanarySlot());
    }
}

} // namespace

int RunMusicKitProbe(int argc, const char *const *argv) {
    if (argc < 2) {
        std::fprintf(g_out, "usage: %s <dir> [--call-instance] [--canary] [--cxx] [--decryptor]\n", argv[0]);
        return 2;
    }
    const std::string dir = argv[1];
    bool callInstance = false, canary = false, cxx = false, decryptor = false;
    for (int i = 2; i < argc; ++i) {
        callInstance |= std::strcmp(argv[i], "--call-instance") == 0;
        canary |= std::strcmp(argv[i], "--canary") == 0;
        cxx |= std::strcmp(argv[i], "--cxx") == 0;
        decryptor |= std::strcmp(argv[i], "--decryptor") == 0;
    }
    std::setvbuf(g_out, nullptr, _IOLBF, 0);

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
        std::fprintf(g_out, "%-4s %s\n", address ? "ok" : "MISS", s.what);
        missing += address ? 0 : 1;
    }

    if (canary) {
        WhereIsCanarySlot();
        std::fprintf(g_out, "canary after dlopen (main): %016" PRIx64 "\n", BionicCanarySlot());
        std::fprintf(g_out, "canary stress main: %s\n", CanaryStressStable() ? "stable" : "CHANGED");
        pthread_t t;
        pthread_create(
            &t, nullptr,
            [](void *) -> void * {
                std::fprintf(g_out, "canary stress new thread (%016" PRIx64 "): %s\n", BionicCanarySlot(),
                            CanaryStressStable() ? "stable" : "CHANGED");
                return nullptr;
            },
            nullptr);
        pthread_join(t, nullptr);
    }

    if (cxx) {
        GotQuery q1{"libappleMusicSDK.so"}, q2{"libc++_ndk1.so"};
        dl_iterate_phdr(ReportBindings, &q1);
        dl_iterate_phdr(ReportBindings, &q2);
        ThrowFromNdkLibcxx(
            dlopen(dir.empty() ? "libc++_ndk1.so" : (dir + "/libc++_ndk1.so").c_str(), RTLD_NOW | RTLD_NOLOAD));
    }

    if (callInstance && missing == 0) {
        using Instance = void *(*)();
        auto instance = reinterpret_cast<Instance>(dlsym(sdk, kSymbols[0].mangled));
        void *ctrl = instance();
        void *again = instance();
        std::fprintf(g_out, "SVFootHillSessionCtrl::instance() = %p (again %p)\n", ctrl, again);
    }

    if (decryptor && missing == 0) TryDecryptor(sdk);
    std::fprintf(g_out, "%s: %d of %zu symbols missing\n", missing ? "FAIL" : "PASS", missing,
                sizeof kSymbols / sizeof kSymbols[0]);
    return missing ? 1 : 0;
}

#ifndef MUSICKIT_PROBE_NO_MAIN
int main(int argc, char **argv) { return RunMusicKitProbe(argc, argv); }
#endif
