package com.gpo.yoin.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gpo.yoin.R
import com.gpo.yoin.data.model.ReleaseType
import com.gpo.yoin.shared.release.YoinReleaseMarks

/**
 * The release-kind filter the artist page's discography has (All / Albums / Singles & EPs /
 * Compilations), shared with Library's Albums view (owner 2026-11-26: "EP 和专辑能不能试着分开？"). Kinds come
 * from the service only (ReleaseType), never guessed from a track count. The filter and its rules are :shared's
 * (`com.gpo.yoin.shared.release.ReleaseKindFilter`), so iOS and HarmonyOS bucket a shelf the same way.
 */
internal typealias ReleaseKindFilter = com.gpo.yoin.shared.release.ReleaseKindFilter

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
    if (YoinReleaseMarks.showsKindTag(type)) releaseKindLabel(type) else null

/**
 * [name] without Apple Music's " - EP" / " - Single" when the cover already says so ([coverKindTag]),
 * so the kind isn't said twice; [name] unchanged otherwise.
 */
internal fun nameWithoutKindSuffix(name: String, type: ReleaseType?): String =
    YoinReleaseMarks.nameWithoutKindSuffix(name, type)
