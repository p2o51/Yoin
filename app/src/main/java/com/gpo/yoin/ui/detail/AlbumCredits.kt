package com.gpo.yoin.ui.detail

import android.content.res.Resources
import androidx.core.os.ConfigurationCompat
import com.gpo.yoin.R
import com.gpo.yoin.data.album.toIso
import com.gpo.yoin.data.model.ReleaseDate
import com.gpo.yoin.shared.albumabout.AlbumAiCredits
import com.gpo.yoin.shared.albumabout.AlbumCreditItem
import com.gpo.yoin.shared.albumabout.AlbumCreditLabels
import com.gpo.yoin.shared.albumabout.AlbumCreditRow
import com.gpo.yoin.shared.albumabout.AlbumCreditsLine
import com.gpo.yoin.shared.albumabout.YoinAlbumAbout
import java.util.Locale

/*
 * The scrapbook's credits line (owner + Carbon 2026-11-25): under the album's title beside the cover, in
 * place of Last Play (which stays on page 1 and on the receipt). Its rules (item order, the service's label
 * first, mastering → mix, the reissue's first release, where the line may break) are :shared's
 * [YoinAlbumAbout], pinned by shared/src/commonTest/resources/golden/album-about.json so iOS and HarmonyOS
 * write the same line; Android supplies its string resources and its date format.
 */

/** The line's items for one album (the service's own labels and dates first). */
internal fun albumCreditItems(
    sourceLabels: List<String>,
    releaseDate: ReleaseDate?,
    originalReleaseDate: ReleaseDate?,
    ai: AlbumAiCredits,
): List<AlbumCreditItem> = YoinAlbumAbout.creditItems(sourceLabels, releaseDate?.toIso(), originalReleaseDate?.toIso(), ai)

/** The line in [resources]' language; null when no item is known (the line is left out, never a placeholder). */
internal fun albumCreditsLine(items: List<AlbumCreditItem>, resources: Resources): AlbumCreditsLine? {
    val locale = resourceLocale(resources)
    return YoinAlbumAbout.creditsLine(items, resourceAlbumCreditLabels(resources), albumCreditDateText(items, locale))
}

/** The credits one by one (label, value), for the sheet's receipt. */
internal fun albumCreditRows(items: List<AlbumCreditItem>, resources: Resources): List<AlbumCreditRow> =
    albumCreditsLine(items, resources)?.rows.orEmpty()

/** The service-dated first release ([AlbumCreditItem.date]) in [locale]'s date format; null when none is dated. */
internal fun albumCreditDateText(items: List<AlbumCreditItem>, locale: Locale): String? =
    items.firstNotNullOfOrNull { ReleaseDate.parseIso(it.date) }?.let { albumReleaseDateText(it, locale) }

/** The words the line is built with, from the app's string resources. */
internal fun resourceAlbumCreditLabels(resources: Resources): AlbumCreditLabels = AlbumCreditLabels(
    label = resources.getString(R.string.detail_album_credit_label),
    producer = resources.getString(R.string.detail_album_credit_producer),
    mastering = resources.getString(R.string.detail_album_credit_mastering),
    mixing = resources.getString(R.string.detail_album_credit_mixing),
    recorded = resources.getString(R.string.detail_album_credit_recorded),
    source = resources.getString(R.string.detail_album_credit_source),
    firstReleased = resources.getString(R.string.detail_album_credit_first_released),
    nameSeparator = resources.getString(R.string.detail_album_credit_name_separator),
    // The format itself (%1$s time, %2$s place): :shared fills it in.
    timeAndPlace = resources.getString(R.string.detail_album_credit_time_place),
)

private fun resourceLocale(resources: Resources): Locale =
    ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault()
