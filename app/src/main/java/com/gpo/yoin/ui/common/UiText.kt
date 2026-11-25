package com.gpo.yoin.ui.common

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.gpo.yoin.shared.albumabout.YoinAlbumAbout

/**
 * Copy that has to leave a ViewModel without a Context.
 *
 * [Res] and [Plural] point at Android resources. [Raw] is for text that is not
 * app copy: track titles, artist names, the user's own notes. Format arguments
 * belong in the resource (`%1$s`, `%1$d`), not in concatenated sentences.
 * A plural whose pattern contains `%1$d` must repeat the count in [Plural.args].
 */
sealed interface UiText {
    data class Res(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Plural(
        @param:PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Raw(val value: String) : UiText
}

@Composable
@ReadOnlyComposable
fun UiText.asString(): String {
    // Same invalidation stringResource uses: configuration, then the resources on this context.
    LocalConfiguration.current
    return asString(LocalContext.current)
}

fun UiText.asString(context: Context): String = when (this) {
    is UiText.Raw -> value
    // A name filled in from copy (the AI service's) sits flush against Chinese: "问 %1$s" → "问千问", "问 Gemini".
    is UiText.Res -> context.getString(id, *args.resolved(context)).let { if (args.any { it is UiText }) YoinAlbumAbout.tightenCjkSpaces(it) else it }
    is UiText.Plural -> context.resources.getQuantityString(id, count, *args.resolved(context))
}

/** Arguments that are copy themselves ([UiText], like the AI service's name) resolve first. */
private fun List<Any>.resolved(context: Context): Array<Any> =
    map { arg -> if (arg is UiText) arg.asString(context) else arg }.toTypedArray()
