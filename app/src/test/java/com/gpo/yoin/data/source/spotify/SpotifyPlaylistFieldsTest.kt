package com.gpo.yoin.data.source.spotify

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playlist reads' `fields` filter keeps every field the DTOs decode: a field added to
 * [SpotifyPlaylistObject] or what it nests, and not to the filter, would silently decode as
 * its default (the review's `release_date_precision` case).
 */
class SpotifyPlaylistFieldsTest {

    /** `a,b(c,d(e)),f` → a → {}, b → {c, d → {e}}, f → {}. */
    private fun parse(fields: String): Map<String, Any> {
        var i = 0
        fun level(): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            while (i < fields.length) {
                val start = i
                while (i < fields.length && fields[i] !in ",()") i++
                val name = fields.substring(start, i)
                var child: Map<String, Any> = emptyMap()
                if (i < fields.length && fields[i] == '(') {
                    i++
                    child = level()
                    i++ // ')'
                }
                if (name.isNotEmpty()) out[name] = child
                if (i < fields.length && fields[i] == ',') {
                    i++
                    continue
                }
                if (i < fields.length && fields[i] == ')') return out
            }
            return out
        }
        return level()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun SerialDescriptor.unwrap(): SerialDescriptor {
        var d = this
        while (d.kind == StructureKind.LIST) d = d.getElementDescriptor(0)
        return d
    }

    /** Every element [descriptor] decodes is named in [projection]; nested classes recursively. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun assertCovers(descriptor: SerialDescriptor, projection: Map<String, Any>, path: String) {
        descriptor.elementNames.forEachIndexed { index, name ->
            assertTrue("$path$name missing from the fields filter", name in projection)
            val child = descriptor.getElementDescriptor(index).unwrap()
            @Suppress("UNCHECKED_CAST")
            val sub = projection[name] as Map<String, Any>
            // A nested object named without a sub-selection gets all of its fields.
            if (child.kind == StructureKind.CLASS && sub.isNotEmpty()) assertCovers(child, sub, "$path$name.")
        }
    }

    @Test
    fun playlistFilterCoversEveryDecodedField() {
        assertCovers(SpotifyPlaylistObject.serializer().descriptor, parse(SpotifyApiClient.PLAYLIST_FIELDS), "")
    }

    @Test
    fun itemsPageFilterCoversEveryDecodedField() {
        val page = SpotifyPagingObject.serializer(SpotifyPlaylistItemObject.serializer()).descriptor
        assertCovers(page, parse(SpotifyApiClient.PLAYLIST_ITEMS_FIELDS), "items.")
    }

    @Test
    fun parserReadsNesting() {
        assertEquals(mapOf("a" to emptyMap<String, Any>(), "b" to mapOf("c" to emptyMap<String, Any>())), parse("a,b(c)"))
    }
}
