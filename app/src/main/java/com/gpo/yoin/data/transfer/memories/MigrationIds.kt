package com.gpo.yoin.data.transfer.memories

import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.SyncFormat

/**
 * Ids for copied notes (song_notes / album_notes ids are global primary keys, so a copy can never reuse the
 * original's): `mig-` + the first 16 hex of sha256(srcScope ␟ srcId ␟ dstScope), ␟ = U+001F. Deterministic,
 * so copying the same note into the same account again finds the earlier copy instead of duplicating it,
 * on any device; never equal to the original id.
 */
object MigrationIds {
    const val PREFIX = "mig-"

    fun derive(srcScope: String, srcId: String, dstScope: String): String =
        PREFIX + CanonicalJson.sha256Hex(SyncFormat.compositeKey(srcScope, srcId, dstScope)).take(16)
}
