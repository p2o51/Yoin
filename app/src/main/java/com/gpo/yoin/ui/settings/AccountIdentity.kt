package com.gpo.yoin.ui.settings

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.MorphPolygonShape
import com.gpo.yoin.ui.settings.service.SetupService
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme

// Who an account is, at a glance. The service is its colour (Spotify green,
// Apple Music rose, your Subsonic server blue), its Yoin Symbol on the
// avatar's badge and its name on the line under the account; the avatar's
// shape + letter say which account, so two logins on one service never look
// alike. Each service owns its hue — no other Settings row uses it. Brand
// logos aren't redrawn.

/** A service's face in Settings: its name, its Yoin Symbol and its hue. */
@Immutable
internal data class ServiceIdentity(
    @param:StringRes @get:StringRes val nameRes: Int,
    val glyph: ImageVector,
    val hue: SettingsHue,
)

internal val ProviderKind.serviceIdentity: ServiceIdentity
    get() = when (this) {
        ProviderKind.SUBSONIC -> ServiceIdentity(
            R.string.settings_service_name_subsonic,
            YoinSymbols.Cloud,
            SettingsHue.Blue,
        )
        ProviderKind.SPOTIFY -> ServiceIdentity(
            R.string.settings_service_name_spotify,
            ServiceMarks.Spotify,
            SettingsHue.Green,
        )
        ProviderKind.APPLE_MUSIC -> ServiceIdentity(
            R.string.settings_service_name_apple,
            ServiceMarks.AppleMusic,
            SettingsHue.Rose,
        )
        ProviderKind.LOCAL -> ServiceIdentity(
            R.string.settings_service_name_local,
            YoinSymbols.Folder,
            SettingsHue.Amber,
        )
    }

internal val SetupService.provider: ProviderKind
    get() = when (this) {
        SetupService.Subsonic -> ProviderKind.SUBSONIC
        SetupService.Spotify -> ProviderKind.SPOTIFY
        SetupService.AppleMusic -> ProviderKind.APPLE_MUSIC
    }

/** How many avatar shapes there are; ≥ [com.gpo.yoin.data.profile.ProfileManager.MAX_PROFILES]. */
internal const val AvatarShapeCount = 8

/**
 * Gives every account its own avatar shape. Walks [profiles] (id to
 * createdAt) oldest first, ties broken by id; each starts at a slot hashed
 * from its id and takes the first one nobody before it has. Ids are stable,
 * so an account keeps its shape across launches; removing an older account
 * can move a newer one that had to probe past it — that change morphs.
 */
internal fun assignAvatarShapes(profiles: List<Pair<String, Long>>): Map<String, Int> {
    val taken = BooleanArray(AvatarShapeCount)
    val result = LinkedHashMap<String, Int>(profiles.size)
    profiles
        .sortedWith(compareBy<Pair<String, Long>> { it.second }.thenBy { it.first })
        .forEach { (id, _) ->
            if (id in result) return@forEach
            val start = Math.floorMod(id.hashCode(), AvatarShapeCount)
            val slot = (0 until AvatarShapeCount)
                .map { (start + it) % AvatarShapeCount }
                .firstOrNull { !taken[it] }
                ?: start
            taken[slot] = true
            result[id] = slot
        }
    return result
}

/**
 * The avatar letter: the first letter or digit of [title] (so "@home" → "H",
 * "陈" → "陈"), title-cased one code point to one. A leading service name is
 * skipped ("Spotify · 31x…" → "3"), so unnamed accounts don't all read the
 * service's initial. Empty when there is none (e.g. only emoji) — the avatar
 * then shows the service glyph instead.
 */
internal fun monogramOf(title: String, serviceName: String? = null): String {
    val name = serviceName
        ?.takeIf { title.startsWith(it, ignoreCase = true) && title.length > it.length }
        ?.let { title.substring(it.length) }
        ?.takeIf { rest -> rest.any(Char::isLetterOrDigit) }
        ?: title
    var index = 0
    while (index < name.length) {
        val codePoint = name.codePointAt(index)
        if (Character.isLetterOrDigit(codePoint)) {
            return String(Character.toChars(Character.toTitleCase(codePoint)))
        }
        index += Character.charCount(codePoint)
    }
    return ""
}

/**
 * The line under an account's name: "Subsonic · music.example.com",
 * "Spotify". The service name drops out when the title already says it
 * (an Apple Music account titled "Apple Music"); null when nothing is left.
 */
@Suppress("ktlint:standard:max-line-length")
internal fun serviceLineOf(
    serviceName: String,
    title: String,
    detail: String?,
    resources: Resources? = null,
): String? {
    val parts = buildList {
        if (!title.contains(serviceName, ignoreCase = true)) add(serviceName)
        detail?.takeIf { it.isNotBlank() && !title.contains(it, ignoreCase = true) }?.let(::add)
    }
    if (resources == null) {
        return parts.takeIf { it.isNotEmpty() }
            ?.joinToString(" · ") // i18n-allow: AccountIdentityTest asserts this English
    }
    return when (parts.size) {
        0 -> null
        1 -> parts[0]
        else -> resources.getString(R.string.settings_service_name_line, parts[0], parts[1])
    }
}

/** Eight clearly different silhouettes — no two scalloped circles. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun avatarPolygon(index: Int): RoundedPolygon = when (Math.floorMod(index, AvatarShapeCount)) {
    0 -> MaterialShapes.Cookie6Sided
    1 -> MaterialShapes.Clover4Leaf
    2 -> MaterialShapes.Gem
    3 -> MaterialShapes.Arch
    4 -> MaterialShapes.Slanted
    5 -> MaterialShapes.Ghostish
    6 -> MaterialShapes.Sunny
    else -> MaterialShapes.Oval
}

/**
 * The avatar's shape. If an account's shape changes (an earlier account it
 * collided with was removed) it morphs to the new one on the spatial spring
 * instead of snapping.
 */
@Composable
private fun rememberAvatarShape(index: Int): Shape {
    val target = avatarPolygon(index)
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    val spec = YoinMotion.spatialSpring<Float>()
    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        from = to
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
    }
    val morph = remember(from, to) { Morph(from, to) }
    val value = progress.value
    return if (value >= 1f) remember(morph) { MorphPolygonShape(morph, 1f) } else MorphPolygonShape(morph, value)
}

/**
 * An account's avatar, in two layers. Behind and large: the SERVICE — the
 * account's own shape filled with the service's accent, the service mark on
 * it (white Spotify arcs on green, the Apple Music notes on rose). In front
 * and small, bottom-end: the PERSON — their picture on the service when we
 * have one (Spotify), else the first letter of their name — ring-cut from the
 * shape by [ringColor] (pass the animated value of whatever sits behind).
 * Shape + badge tell two accounts on one service apart. Decorative for
 * accessibility: the name and service are text beside it.
 */
@Composable
internal fun AccountAvatar(
    monogram: String,
    shapeIndex: Int,
    identity: ServiceIdentity,
    ringColor: Color,
    modifier: Modifier = Modifier,
    photoUrl: String? = null,
    size: Dp = 48.dp,
) {
    val tone = identity.hue.tone()
    val showBadge = monogram.isNotEmpty() || photoUrl != null
    val badge = (size * 0.46f).coerceAtLeast(AvatarBadgeMinSize)
    val ring = (size.value / 24f).coerceIn(1.5f, 3f).dp
    // The badge's letter scales with the badge, not the font scale: it's a
    // mark, and a large font would spill it out of the circle.
    val letterSize = with(LocalDensity.current) { (badge * 0.5f).toSp() }
    Box(modifier = modifier.size(size).clearAndSetSemantics {}) {
        Surface(
            modifier = Modifier.size(size),
            shape = rememberAvatarShape(shapeIndex),
            color = tone.accent,
            contentColor = tone.onAccent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = identity.glyph,
                    contentDescription = null,
                    // With a badge, the mark leans toward top-start so the
                    // badge never bites it (offset follows RTL).
                    modifier = Modifier
                        .then(if (showBadge) Modifier.offset(x = -size * 0.07f, y = -size * 0.07f) else Modifier)
                        .size(size * 0.54f),
                )
            }
        }
        if (showBadge) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = ring, y = ring)
                    .size(badge + ring * 2),
                shape = CircleShape,
                color = ringColor,
            ) {
                Surface(
                    modifier = Modifier.padding(ring),
                    shape = CircleShape,
                    color = tone.iconContainer,
                    contentColor = tone.iconContent,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (monogram.isNotEmpty()) {
                            Text(
                                text = monogram,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = letterSize,
                                    lineHeight = letterSize,
                                ),
                                maxLines = 1,
                            )
                        }
                        if (photoUrl != null) {
                            // The letter stays underneath while the picture
                            // loads (and if it never does).
                            AsyncImage(
                                model = photoUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Smallest legible badge: room for one letter or a face. */
private val AvatarBadgeMinSize = 18.dp

/**
 * "Spotify" / "Subsonic · host" under an account's name — which service it
 * lives on. Text only: the avatar's badge already carries the glyph.
 */
@Composable
internal fun ServiceLine(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
private fun AccountAvatarPreview() {
    YoinTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProviderKind.entries.forEachIndexed { index, provider ->
                AccountAvatar(
                    monogram = monogramOf(stringResource(provider.serviceIdentity.nameRes)),
                    shapeIndex = index,
                    identity = provider.serviceIdentity,
                    ringColor = MaterialTheme.colorScheme.surfaceBright,
                )
            }
        }
    }
}
