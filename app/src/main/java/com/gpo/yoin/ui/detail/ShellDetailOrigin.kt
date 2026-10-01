package com.gpo.yoin.ui.detail

import com.gpo.yoin.ui.experience.ExperienceSessionState
import com.gpo.yoin.ui.experience.HomeSurface
import com.gpo.yoin.ui.navigation.YoinSection

/** Compact shell overlays have no visible bottom bar to hand off to a detail. */
internal val ExperienceSessionState.hasOverlayHidingBottomBar: Boolean
    get() = nowPlayingExpanded ||
        (selectedSection == YoinSection.HOME && homeSurface == HomeSurface.Memories)
