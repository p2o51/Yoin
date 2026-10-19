import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RelroPageEndTest {
    // libappleMusicSDK.so (arm64-v8a, mediaplayback 1.1.1): RELRO 0x670000-0x679000 inside the RW segment.
    private val appleMusicSdk = listOf(
        Segment(PT_LOAD, R or X, vaddr = 0x0, filesz = 0x106194, memsz = 0x106194),
        Segment(PT_LOAD, R or X, vaddr = 0x110000, filesz = 0x52d300, memsz = 0x52d300),
        Segment(PT_LOAD, R or W, vaddr = 0x640000, filesz = 0x3a7c8, memsz = 0x432f0),
        Segment(PT_GNU_RELRO, R, vaddr = 0x670000, filesz = 0x9000, memsz = 0x9000),
    )

    // libc++_shared.so (arm64-v8a, same AAR): RELRO starts its RW segment off a page, 0xf5b18-0xfe000.
    private val cxxShared = listOf(
        Segment(PT_LOAD, R or X, vaddr = 0x0, filesz = 0xe58d4, memsz = 0xe58d4),
        Segment(PT_LOAD, R or W, vaddr = 0xf5b18, filesz = 0x8a20, memsz = 0xb8e8),
        Segment(PT_GNU_RELRO, R, vaddr = 0xf5b18, filesz = 0x84e8, memsz = 0x84e8),
    )

    @Test
    fun should_endRelroOnTheLastWholePage_when_itEndsInsideOne() {
        val elf = elf64(appleMusicSdk)
        assertEquals(RelroAlignment.Shortened(0x670000, 0x679000, 0x678000), alignRelroEnd(elf, PAGE))
        assertEquals(0x8000L, relro(elf).memsz)
        assertEquals(0x8000L, relro(elf).filesz)
    }

    @Test
    fun should_keepRelroStart_when_itStartsOffAPage() {
        val elf = elf64(cxxShared)
        assertEquals(RelroAlignment.Shortened(0xf5b18, 0xfe000, 0xfc000), alignRelroEnd(elf, PAGE))
        assertEquals(0xf5b18L, relro(elf).vaddr)
        assertEquals(0xfc000L - 0xf5b18, relro(elf).memsz)
    }

    @Test
    fun should_changeOnlyRelroSizes_when_shortening() {
        val before = elf64(appleMusicSdk)
        val after = before.copyOf()
        alignRelroEnd(after, PAGE)
        val relroAt = PHDR_OFFSET + 3 * PHDR_SIZE
        val sizes = (relroAt + 32 until relroAt + 48).toSet()
        val changed = before.indices.filter { before[it] != after[it] }
        assertTrue("changed bytes $changed", changed.isNotEmpty() && changed.all { it in sizes })
    }

    @Test
    fun should_leaveTheLibraryAlone_when_relroAlreadyEndsOnAPage() {
        val elf = elf64(appleMusicSdk.dropLast(1) + Segment(PT_GNU_RELRO, R, 0x670000, 0x8000, 0x8000))
        val before = elf.copyOf()
        assertEquals(RelroAlignment.AlreadyAligned, alignRelroEnd(elf, PAGE))
        assertArrayEquals(before, elf)
    }

    @Test
    fun should_skip_when_theLibraryIs32Bit() {
        val elf = elf64(appleMusicSdk).also { it[4] = 1 }
        val before = elf.copyOf()
        assertEquals(RelroAlignment.NotElf64, alignRelroEnd(elf, PAGE))
        assertArrayEquals(before, elf)
    }

    @Test
    fun should_report_when_thereIsNoRelro() {
        assertEquals(RelroAlignment.NoRelro, alignRelroEnd(elf64(appleMusicSdk.dropLast(1)), PAGE))
    }

    @Test
    fun should_refuse_when_writableDataSitsBeforeRelroOnItsFirstPage() {
        // RELRO starts mid-page inside its RW segment: bionic's rounded start would cover data the end can't save.
        val elf = elf64(
            listOf(
                Segment(PT_LOAD, R or W, vaddr = 0x8000, filesz = 0x9000, memsz = 0x9000),
                Segment(PT_GNU_RELRO, R, vaddr = 0x9000, filesz = 0x5800, memsz = 0x5800),
            ),
        )
        try {
            alignRelroEnd(elf, PAGE)
            fail("RELRO with writable data on its first page must not be called fixed")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("writable data"))
        }
    }

    @Test
    fun should_refuse_when_relroIsShorterThanAPage() {
        val elf = elf64(listOf(Segment(PT_GNU_RELRO, R, vaddr = 0x4000, filesz = 0x1000, memsz = 0x1000)))
        try {
            alignRelroEnd(elf, PAGE)
            fail("a RELRO under one page can't end on one")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("whole"))
        }
    }

    private data class Segment(val type: Int, val flags: Int, val vaddr: Long, val filesz: Long, val memsz: Long)

    private fun elf64(segments: List<Segment>): ByteArray {
        val bytes = ByteBuffer.allocate(PHDR_OFFSET + segments.size * PHDR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        bytes.putLong(0x20, PHDR_OFFSET.toLong())
        bytes.putShort(0x36, PHDR_SIZE.toShort())
        bytes.putShort(0x38, segments.size.toShort())
        segments.forEachIndexed { index, segment ->
            val at = PHDR_OFFSET + index * PHDR_SIZE
            bytes.putInt(at, segment.type)
            bytes.putInt(at + 4, segment.flags)
            bytes.putLong(at + 8, segment.vaddr)
            bytes.putLong(at + 16, segment.vaddr)
            bytes.putLong(at + 24, segment.vaddr)
            bytes.putLong(at + 32, segment.filesz)
            bytes.putLong(at + 40, segment.memsz)
            bytes.putLong(at + 48, if (segment.type == PT_LOAD) 0x10000 else 1)
        }
        return bytes.array()
    }

    private fun relro(elf: ByteArray): Segment {
        val bytes = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
        val count = bytes.getShort(0x38).toInt()
        val at = (0 until count).map { PHDR_OFFSET + it * PHDR_SIZE }.single { bytes.getInt(it) == PT_GNU_RELRO }
        return Segment(
            PT_GNU_RELRO,
            flags = bytes.getInt(at + 4),
            vaddr = bytes.getLong(at + 16),
            filesz = bytes.getLong(at + 32),
            memsz = bytes.getLong(at + 40),
        )
    }

    private companion object {
        const val PAGE = 16_384L
        const val PHDR_OFFSET = 64
        const val PHDR_SIZE = 56
        const val PT_LOAD = 1
        const val R = 4
        const val W = 2
        const val X = 1
    }
}
