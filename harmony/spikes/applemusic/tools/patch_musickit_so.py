#!/usr/bin/env python3
"""Prepare Apple's Android MusicKit native libraries for HarmonyOS's musl loader.

Feasibility spike only (docs/applemusic.md). Writes a patched COPY; the input is never changed,
and neither file belongs in the repository (Apple's binaries are not redistributable).

Everything is done in place inside the existing ELF layout (no section or segment moves,
unlike patchelf, so program headers stay where the loader and any later RELRO fix expect them):

1. DT_NEEDED / DT_SONAME renames, written over the old string (the new name must not be longer):
     --preset sdk     libappleMusicSDK.so: liblog.so, libOpenSLES.so, libandroid.so -> libam.so,
                      libc++_shared.so -> libc++_ndk1.so
     --preset libcxx  libc++_shared.so: libdl.so -> libam.so (pulls the shim in through DT_NEEDED),
                      SONAME libc++_shared.so -> libc++_ndk1.so (no clash with an OHOS libc++_shared.so)
2. Symbol versions: every undefined symbol's .gnu.version entry is set to 1 (global), and
   DT_VERNEED / DT_VERNEEDNUM are removed, so bionic's "LIBC" version requirements don't reach musl.
3. Report only: R_AARCH64_RELATIVE addends outside the image (AMFOrHarmonyOS_Base found such
   relocations in other Apple libraries) and whether PT_GNU_RELRO ends on a 4 KB / 16 KB page.

Usage: python3 -I patch_musickit_so.py --preset sdk|libcxx <input.so> <output.so>
"""

import argparse
import shutil
import struct
import sys

PT_LOAD, PT_DYNAMIC, PT_GNU_RELRO = 1, 2, 0x6474E552
DT_NULL, DT_NEEDED, DT_STRTAB, DT_SYMTAB, DT_RELA, DT_RELASZ, DT_STRSZ, DT_SONAME = 0, 1, 5, 6, 7, 8, 10, 14
DT_HASH, DT_GNU_HASH = 4, 0x6FFFFEF5
DT_VERSYM, DT_VERNEED, DT_VERNEEDNUM = 0x6FFFFFF0, 0x6FFFFFFE, 0x6FFFFFFF
R_AARCH64_RELATIVE = 1027
EM_AARCH64 = 183

PRESETS = {
    "sdk": {
        "liblog.so": "libam.so",
        "libOpenSLES.so": "libam.so",
        "libandroid.so": "libam.so",
        "libc++_shared.so": "libc++_ndk1.so",
    },
    "libcxx": {
        "libdl.so": "libam.so",
        "libc++_shared.so": "libc++_ndk1.so",
    },
}


class Elf:
    def __init__(self, data: bytearray):
        if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
            raise SystemExit("not a little-endian ELF64 file")
        if struct.unpack_from("<H", data, 0x12)[0] != EM_AARCH64:
            raise SystemExit("not an AArch64 library (patch the arm64-v8a copy)")
        self.data = data
        phoff = struct.unpack_from("<Q", data, 0x20)[0]
        phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
        self.phdrs = []
        for i in range(phnum):
            p_type, _, p_offset, p_vaddr, _, p_filesz, p_memsz, _ = struct.unpack_from(
                "<IIQQQQQQ", data, phoff + i * phentsize)
            self.phdrs.append((p_type, p_offset, p_vaddr, p_filesz, p_memsz))
        dyn = [p for p in self.phdrs if p[0] == PT_DYNAMIC]
        if not dyn:
            raise SystemExit("no PT_DYNAMIC")
        self.dyn_off, self.dyn_size = dyn[0][1], dyn[0][3]
        self.dynamic = []
        for off in range(self.dyn_off, self.dyn_off + self.dyn_size, 16):
            tag, val = struct.unpack_from("<qQ", data, off)
            self.dynamic.append((tag, val))
            if tag == DT_NULL:
                break

    def offset(self, vaddr: int) -> int:
        for p_type, p_offset, p_vaddr, p_filesz, _ in self.phdrs:
            if p_type == PT_LOAD and p_vaddr <= vaddr < p_vaddr + p_filesz:
                return vaddr - p_vaddr + p_offset
        raise SystemExit(f"address {vaddr:#x} not in a file-backed PT_LOAD")

    def tag(self, tag: int):
        return next((v for t, v in self.dynamic if t == tag), None)

    def string(self, strtab: int, index: int) -> str:
        start = strtab + index
        return self.data[start:self.data.index(0, start)].decode()

    def symbol_count(self) -> int:
        hash_vaddr = self.tag(DT_HASH)
        if hash_vaddr is not None:
            return struct.unpack_from("<II", self.data, self.offset(hash_vaddr))[1]
        gnu = self.tag(DT_GNU_HASH)
        if gnu is None:
            raise SystemExit("no DT_HASH or DT_GNU_HASH: cannot count symbols")
        off = self.offset(gnu)
        nbuckets, symoffset, bloom_size, _ = struct.unpack_from("<IIII", self.data, off)
        buckets = off + 16 + 8 * bloom_size
        last = max(struct.unpack_from(f"<{nbuckets}I", self.data, buckets))
        if last < symoffset:
            return symoffset
        chain = buckets + 4 * nbuckets
        while not struct.unpack_from("<I", self.data, chain + 4 * (last - symoffset))[0] & 1:
            last += 1
        return last + 1


def rename_strings(elf: Elf, renames: dict, strtab: int) -> list:
    """Overwrites DT_NEEDED / DT_SONAME strings; refuses when another reference shares the bytes."""
    symtab = elf.offset(elf.tag(DT_SYMTAB))
    nsyms = elf.symbol_count()
    symbol_names = {struct.unpack_from("<I", elf.data, symtab + 24 * i)[0] for i in range(nsyms)}
    done = []
    for tag, value in elf.dynamic:
        if tag not in (DT_NEEDED, DT_SONAME):
            continue
        old = elf.string(strtab, value)
        new = renames.get(old)
        if new is None:
            continue
        if len(new) > len(old):
            raise SystemExit(f"{new!r} is longer than {old!r}: cannot rename in place")
        span = range(value, value + len(old) + 1)
        if any(index in span for index in symbol_names if index):
            raise SystemExit(f"{old!r} shares its bytes with a symbol name: refusing")
        start = strtab + value
        elf.data[start:start + len(old)] = new.encode().ljust(len(old), b"\0")
        done.append(f"{'SONAME' if tag == DT_SONAME else 'NEEDED'} {old} -> {new}")
    return done


def clear_versions(elf: Elf) -> list:
    versym = elf.tag(DT_VERSYM)
    notes = []
    if versym is not None:
        symtab = elf.offset(elf.tag(DT_SYMTAB))
        table = elf.offset(versym)
        cleared = 0
        for i in range(elf.symbol_count()):
            shndx = struct.unpack_from("<H", elf.data, symtab + 24 * i + 6)[0]
            ver = struct.unpack_from("<H", elf.data, table + 2 * i)[0]
            if shndx == 0 and (ver & 0x7FFF) > 1:
                struct.pack_into("<H", elf.data, table + 2 * i, 1)
                cleared += 1
        notes.append(f"versions cleared on {cleared} undefined symbols")
    kept = [(t, v) for t, v in elf.dynamic if t not in (DT_VERNEED, DT_VERNEEDNUM)]
    if len(kept) != len(elf.dynamic):
        slots = elf.dyn_size // 16
        kept = [(t, v) for t, v in kept if t != DT_NULL]
        kept += [(DT_NULL, 0)] * (slots - len(kept))
        for i, (t, v) in enumerate(kept):
            struct.pack_into("<qQ", elf.data, elf.dyn_off + 16 * i, t, v)
        notes.append("DT_VERNEED / DT_VERNEEDNUM removed")
    return notes


def report(elf: Elf) -> list:
    notes = []
    image_end = max(v + m for t, _, v, _, m in elf.phdrs if t == PT_LOAD)
    rela, relasz = elf.tag(DT_RELA), elf.tag(DT_RELASZ)
    if rela is not None and relasz:
        off = elf.offset(rela)
        bad = sum(
            1 for i in range(relasz // 24)
            if (struct.unpack_from("<QQq", elf.data, off + 24 * i)[1] & 0xFFFFFFFF) == R_AARCH64_RELATIVE
            and not 0 <= struct.unpack_from("<QQq", elf.data, off + 24 * i)[2] < image_end)
        notes.append(f"R_AARCH64_RELATIVE addends outside the image: {bad}")
    for t, _, vaddr, _, memsz in elf.phdrs:
        if t == PT_GNU_RELRO:
            end = vaddr + memsz
            notes.append(f"RELRO ends at {end:#x}: 4K-aligned={end % 0x1000 == 0} 16K-aligned={end % 0x4000 == 0}")
    return notes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--preset", choices=sorted(PRESETS), required=True)
    parser.add_argument("input")
    parser.add_argument("output")
    args = parser.parse_args()
    if args.input == args.output:
        raise SystemExit("write to a new file: the input stays as Apple shipped it")
    shutil.copyfile(args.input, args.output)
    with open(args.output, "rb") as f:
        elf = Elf(bytearray(f.read()))
    strtab = elf.offset(elf.tag(DT_STRTAB))
    notes = rename_strings(elf, PRESETS[args.preset], strtab)
    notes += clear_versions(elf)
    notes += report(elf)
    with open(args.output, "wb") as f:
        f.write(elf.data)
    for note in notes:
        print(note)
    return 0


if __name__ == "__main__":
    sys.exit(main())
