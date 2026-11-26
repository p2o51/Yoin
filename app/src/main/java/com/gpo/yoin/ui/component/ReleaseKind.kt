package com.gpo.yoin.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gpo.yoin.R
import com.gpo.yoin.data.model.ReleaseType

/**
 * The release-kind filter the artist page's discography has (All / Albums / Singles & EPs /
 * Compilations), shared with Library's Albums view (owner 2026-11-26: "EP 和专辑能不能试着分开？"). Kinds come from the service only (ReleaseType), never guessed from a track count.
 */
internal enum class ReleaseKindFilter {
    All,
    Albums,
    SinglesAndEps,
    Compilations,
    ;

    fun accepts(type: ReleaseType?): Boolean = when (this) {
        All -> true
        Albums -> type == ReleaseType.Album
        SinglesAndEps -> type == ReleaseType.Single || type == ReleaseType.EP
        Compilations -> type == ReleaseType.Compilation
    }

    companion object {
        /** The filters worth showing for [types]: none unless the list holds at least two kinds. */
        fun offeredFor(types: List<ReleaseType?>): List<ReleaseKindFilter> {
            val kinds = entries.drop(1).filter { f -> types.any { f.accepts(it) } }
            return if (kinds.size >= 2) listOf(All) + kinds else emptyList()
        }
    }
}

@Composable
internal fun releaseKindFilterLabel(filter: ReleaseKindFilter): String = when (filter) {
    ReleaseKindFilter.All -> stringResource(R.string.detail_artist_filter_all)
    ReleaseKindFilter.Albums -> stringResource(R.string.detail_artist_filter_albums)
    ReleaseKindFilter.SinglesAndEps -> stringResource(R.string.detail_artist_filter_singles)
    ReleaseKindFilter.Compilations -> stringResource(R.string.detail_artist_filter_compilations)
}

/** A release's kind as a word ("Album", "EP", "Single", "Compilation"); null when unknown. */
@Composable
internal fun releaseKindLabel(type: ReleaseType?): String? = when (type) {
    ReleaseType.Album -> stringResource(R.string.detail_artist_kind_album)
    ReleaseType.EP -> stringResource(R.string.detail_artist_kind_ep)
    ReleaseType.Single -> stringResource(R.string.detail_artist_kind_single)
    ReleaseType.Compilation -> stringResource(R.string.detail_artist_kind_compilation)
    null -> null
}

/** The kind worth a tag on a cover: an EP or a single (an album is the default; a compilation says so in Library's filter). */
@Composable
internal fun coverKindTag(type: ReleaseType?): String? =
    if (type == ReleaseType.EP || type == ReleaseType.Single) releaseKindLabel(type) else null

/**
 * [name] without Apple Music's " - EP" / " - Single" when the cover already says so ([coverKindTag]),
 * so the kind isn't said twice; [name] unchanged otherwise.
 */
internal fun nameWithoutKindSuffix(name: String, type: ReleaseType?): String = when (type) {
    ReleaseType.EP -> name.removeSuffix(" - EP")
    ReleaseType.Single -> name.removeSuffix(" - Single")
    else -> name
}.ifBlank { name }
