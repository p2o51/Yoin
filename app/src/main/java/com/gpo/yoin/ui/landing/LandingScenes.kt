package com.gpo.yoin.ui.landing

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import com.gpo.yoin.R
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.MorphPolygonShape
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.AccountAvatar
import com.gpo.yoin.ui.settings.monogramOf
import com.gpo.yoin.ui.settings.provider
import com.gpo.yoin.ui.settings.serviceIdentity
import com.gpo.yoin.ui.settings.service.SetupService
import com.gpo.yoin.ui.settings.tone
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme

/** A step's content inside the floating window: scrolls when it outgrows it, centred when the window is taller. */
@Composable
internal fun LandingSceneColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

/** The section label inside the window (Settings' group label, a step smaller). */
@Composable
internal fun LandingLabel(@StringRes text: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 8.dp),
    )
}

// ── Pick ────────────────────────────────────────────────────────────────

@Composable
internal fun PickScene(
    picked: Set<SetupService>,
    onToggle: (SetupService) -> Unit,
) {
    LandingSceneColumn {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SetupService.entries.forEach { service ->
                ServiceTile(
                    service = service,
                    selected = service in picked,
                    onClick = { onToggle(service) },
                )
            }
        }
    }
}

@StringRes
private fun SetupService.pickLine(): Int = when (this) {
    SetupService.Subsonic -> R.string.landing_pick_subsonic
    SetupService.Spotify -> R.string.landing_pick_spotify
    SetupService.AppleMusic -> R.string.landing_pick_apple
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServiceTile(
    service: SetupService,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val identity = service.provider.serviceIdentity
    val tone = identity.hue.tone()
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberYoinHaptics()
    val background by animateColorAsState(if (selected) tone.container else scheme.surfaceBright, YoinMotion.defaultEffectsSpec(), label = "tileBg")
    val contentColor by animateColorAsState(if (selected) tone.onContainer else scheme.onSurface, YoinMotion.defaultEffectsSpec(), label = "tileFg")
    val corner by animateDpAsState(if (selected) 32.dp else 24.dp, YoinMotion.defaultSpatialSpec(), label = "tileCorner")
    // Circle → nine-lobed cookie, with an eighth of a turn, when picked.
    val morphProgress by animateFloatAsState(if (selected) 1f else 0f, YoinMotion.fastSpatialSpec(), label = "tileMorph")
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val avatarFill by animateColorAsState(if (selected) tone.accent else tone.iconContainer, YoinMotion.defaultEffectsSpec(), label = "avatarFill")
    val avatarGlyph by animateColorAsState(if (selected) tone.onAccent else tone.iconContent, YoinMotion.defaultEffectsSpec(), label = "avatarGlyph")
    val checkFill by animateColorAsState(if (selected) tone.accent else scheme.surfaceBright.copy(alpha = 0f), YoinMotion.defaultEffectsSpec(), label = "checkFill")
    val checkBorder by animateColorAsState(if (selected) tone.accent else scheme.outline, YoinMotion.defaultEffectsSpec(), label = "checkBorder")
    val checkScale by animateFloatAsState(if (selected) 1f else 0f, YoinMotion.fastSpatialSpec(), label = "checkScale")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .background(background)
            .semantics {
                role = Role.Checkbox
                this.selected = selected
            }
            .clickable {
                haptics.performToggle(!selected)
                onClick()
            }
            .padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer { rotationZ = morphProgress * 22.5f }
                .background(avatarFill, MorphPolygonShape(morph, morphProgress)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = identity.glyph,
                contentDescription = null,
                tint = avatarGlyph,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = -morphProgress * 22.5f },
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(identity.nameRes),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor,
            )
            Text(
                text = stringResource(service.pickLine()),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.78f),
            )
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(checkFill, CircleShape)
                .border(2.dp, checkBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = YoinSymbols.Check,
                contentDescription = null,
                tint = tone.onAccent,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer {
                        scaleX = checkScale
                        scaleY = checkScale
                    },
            )
        }
    }
}

// ── About ───────────────────────────────────────────────────────────────

@Composable
internal fun AboutScene(picked: Set<SetupService>) {
    LandingSceneColumn {
        LandingLabel(R.string.landing_about_does)
        LandingGroup {
            listOf(R.string.landing_does_play, R.string.landing_does_notes, R.string.landing_does_accounts, R.string.landing_does_passwords)
                .forEach { MarkRow(text = it, yes = true) }
        }
        LandingLabel(R.string.landing_about_doesnt)
        LandingGroup {
            listOf(R.string.landing_dont_sell, R.string.landing_dont_host, R.string.landing_dont_bill)
                .forEach { MarkRow(text = it, yes = false) }
        }
        LandingLabel(R.string.landing_about_need)
        LandingGroup {
            val services = SetupService.entries.filter { it in picked }
            if (services.isEmpty()) {
                MarkRow(text = R.string.landing_need_none, yes = false)
            } else {
                services.forEach { service ->
                    ServiceRow(
                        service = service,
                        text = when (service) {
                            SetupService.Subsonic -> R.string.landing_need_subsonic
                            SetupService.Spotify -> R.string.landing_need_spotify
                            SetupService.AppleMusic -> R.string.landing_need_apple
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun LandingGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceBright)
            .padding(vertical = 5.dp),
        content = content,
    )
}

@Composable
private fun MarkRow(@StringRes text: Int, yes: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(if (yes) scheme.primary else scheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (yes) YoinSymbols.Check else YoinSymbols.Close,
                contentDescription = null,
                tint = if (yes) scheme.onPrimary else scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServiceRow(service: SetupService, @StringRes text: Int) {
    val identity = service.provider.serviceIdentity
    val tone = identity.hue.tone()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(tone.accent, MorphPolygonShape(remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Cookie9Sided) }, 0f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(identity.glyph, contentDescription = null, tint = tone.onAccent, modifier = Modifier.size(15.dp))
        }
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ── Ready ───────────────────────────────────────────────────────────────

/** An account the Ready step lists. */
@Immutable
internal data class LandingAccount(
    val id: String,
    val name: String,
    val provider: ProviderKind,
    val shapeIndex: Int,
    val inUse: Boolean,
)

@Composable
internal fun ReadyScene(accounts: List<LandingAccount>) {
    LandingSceneColumn {
        LandingLabel(R.string.landing_ready_accounts)
        if (accounts.isEmpty()) {
            Text(
                text = stringResource(R.string.landing_ready_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 16.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                accounts.forEachIndexed { index, account ->
                    AccountRow(account, first = index == 0, last = index == accounts.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun AccountRow(account: LandingAccount, first: Boolean, last: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val identity = account.provider.serviceIdentity
    val serviceName = stringResource(identity.nameRes)
    val shape = RoundedCornerShape(
        topStart = if (first) 20.dp else 4.dp,
        topEnd = if (first) 20.dp else 4.dp,
        bottomStart = if (last) 20.dp else 4.dp,
        bottomEnd = if (last) 20.dp else 4.dp,
    )
    Surface(shape = shape, color = scheme.surfaceBright, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AccountAvatar(
                monogram = monogramOf(account.name, serviceName),
                shapeIndex = account.shapeIndex,
                identity = identity,
                ringColor = scheme.surfaceBright,
                size = 40.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(account.name, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(serviceName, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            if (account.inUse) {
                Text(
                    text = stringResource(R.string.landing_ready_in_use),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onPrimary,
                    modifier = Modifier
                        .background(scheme.primary, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PickScenePreview() {
    YoinTheme {
        PickScene(picked = setOf(SetupService.Spotify), onToggle = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutScenePreview() {
    YoinTheme {
        AboutScene(picked = setOf(SetupService.Subsonic, SetupService.AppleMusic))
    }
}
