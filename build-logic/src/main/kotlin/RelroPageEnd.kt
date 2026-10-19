import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What [alignRelroEnd] found in, or did to, one shared library.
 */
internal sealed interface RelroAlignment {
    /** Its `PT_GNU_RELRO` now ends at [newEnd] instead of [oldEnd] (virtual addresses), a page boundary. */
    data class Shortened(val start: Long, val oldEnd: Long, val newEnd: Long) : RelroAlignment

    data object AlreadyAligned : RelroAlignment

    /** Not a 64-bit little-endian ELF: no 16 KB page device runs it (they are all arm64 or x86_64). */
    data object NotElf64 : RelroAlignment

    data object NoRelro : RelroAlignment
}

/**
 * Ends [elf]'s `PT_GNU_RELRO` on a [pageSize] boundary, in place, by dropping the tail past the last whole page.
 *
 * Bionic write-protects RELRO from `page_start(p_vaddr)` to `page_end(p_vaddr + p_memsz)`. When RELRO ends inside
 * a page, as older NDK linkers left it, that rounds up over the writable data that follows in the same segment.
 * On 4 KB pages these libraries end RELRO on a page; on 16 KB pages the first data after it turns read-only and
 * the library's first write there crashes, and Android 17's linker refuses such a library up front when that
 * end leaves its alignment under the page size. With RELRO ending on the page the rounding stops at RELRO's end:
 * the dropped tail (under one page) stays writable, which costs that much hardening and nothing else. Only the
 * program header changes; code, data and every other header stay byte for byte.
 */
internal fun alignRelroEnd(elf: ByteArray, pageSize: Long): RelroAlignment {
    require(pageSize > 0 && pageSize and (pageSize - 1) == 0L) { "page size $pageSize is not a power of two" }
    if (!isElf64LittleEndian(elf)) return RelroAlignment.NotElf64
    val bytes = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
    val headers = programHeaders(bytes)
    val relro = headers.filter { it.type == PT_GNU_RELRO }
    if (relro.isEmpty()) return RelroAlignment.NoRelro
    val header = relro.singleOrNull() ?: error("${relro.size} PT_GNU_RELRO headers, expected one")
    val start = header.vaddr
    val oldEnd = start + header.memsz
    if (oldEnd % pageSize == 0L) return RelroAlignment.AlreadyAligned
    val newEnd = oldEnd - oldEnd % pageSize
    check(newEnd > start) { "RELRO 0x${start.hex()}-0x${oldEnd.hex()} doesn't span a whole $pageSize-byte page" }
    // Bionic rounds the start down too. Shortening the end can't help if writable data sits before RELRO on its
    // first page, in its own segment or another.
    val pageStart = start - start % pageSize
    val overlap = if (pageStart == start) {
        null
    } else {
        headers.firstOrNull {
            it.type == PT_LOAD && it.flags and PF_W != 0 && it.vaddr < start && it.vaddr + it.memsz > pageStart
        }
    }
    check(overlap == null) {
        "RELRO's first page 0x${pageStart.hex()} holds writable data of the segment at 0x${overlap!!.vaddr.hex()}"
    }
    val memsz = newEnd - start
    bytes.putLong(header.offset + P_MEMSZ, memsz)
    bytes.putLong(header.offset + P_FILESZ, minOf(header.filesz, memsz))
    return RelroAlignment.Shortened(start, oldEnd, newEnd)
}

private class ProgramHeader(
    val offset: Int,
    val type: Int,
    val flags: Int,
    val vaddr: Long,
    val filesz: Long,
    val memsz: Long,
)

private fun isElf64LittleEndian(elf: ByteArray): Boolean =
    elf.size >= ELF64_HEADER_SIZE &&
        elf[0] == 0x7f.toByte() && elf[1] == 'E'.code.toByte() && elf[2] == 'L'.code.toByte() &&
        elf[3] == 'F'.code.toByte() && elf[EI_CLASS] == ELFCLASS64 && elf[EI_DATA] == ELFDATA2LSB

private fun programHeaders(bytes: ByteBuffer): List<ProgramHeader> {
    val tableOffset = bytes.getLong(E_PHOFF)
    val entrySize = bytes.getShort(E_PHENTSIZE).toInt() and 0xffff
    val count = bytes.getShort(E_PHNUM).toInt() and 0xffff
    check(entrySize == ELF64_PHDR_SIZE) { "program header entries of $entrySize bytes, expected $ELF64_PHDR_SIZE" }
    check(tableOffset >= 0 && tableOffset + count.toLong() * entrySize <= bytes.capacity()) {
        "program header table runs past the end of the file"
    }
    return (0 until count).map { index ->
        val at = tableOffset.toInt() + index * entrySize
        ProgramHeader(
            offset = at,
            type = bytes.getInt(at),
            flags = bytes.getInt(at + P_FLAGS),
            vaddr = bytes.getLong(at + P_VADDR),
            filesz = bytes.getLong(at + P_FILESZ),
            memsz = bytes.getLong(at + P_MEMSZ),
        )
    }
}

internal fun Long.hex(): String = java.lang.Long.toHexString(this)

private const val ELF64_HEADER_SIZE = 64
private const val EI_CLASS = 4
private const val EI_DATA = 5
private const val ELFCLASS64: Byte = 2
private const val ELFDATA2LSB: Byte = 1
private const val E_PHOFF = 0x20
private const val E_PHENTSIZE = 0x36
private const val E_PHNUM = 0x38
private const val ELF64_PHDR_SIZE = 56
private const val P_FLAGS = 4
private const val P_VADDR = 16
private const val P_FILESZ = 32
private const val P_MEMSZ = 40
private const val PT_LOAD = 1
internal const val PT_GNU_RELRO = 0x6474e552
private const val PF_W = 2
