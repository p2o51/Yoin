package com.gpo.yoin.ui.navigation.back

import com.gpo.yoin.ui.experience.HomeSurface
import com.gpo.yoin.ui.navigation.YoinSection

enum class ShellBackOwner {
    None,
    Memories,
    NowPlaying,
}

fun resolveShellBackOwner(
    showNowPlaying: Boolean,
    selectedSection: YoinSection,
    homeSurface: HomeSurface,
): ShellBackOwner = when {
    showNowPlaying -> ShellBackOwner.NowPlaying
    selectedSection == YoinSection.HOME && homeSurface == HomeSurface.Memories -> {
        ShellBackOwner.Memories
    }

    else -> ShellBackOwner.None
}
