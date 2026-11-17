package com.gpo.yoin.ui.experience

/**
 * The Memories reveal and the Home surface the shell's back resolver reads
 * must never stay out of step: a reveal open with the surface still Feed
 * registers no back handler, and the system back leaves the app.
 *
 * Called when the reveal comes to REST at an endpoint ([restedAt] 0f open,
 * 1f closed — `RevealState.restingEndpoint`), never on a surface change: a
 * back that has just set Feed while the page still sits open must not be
 * undone. Returns the surface to write, or null when the two agree.
 * - rests open while the surface is Feed (a drag carried it open after its
 *   settle was interrupted) → Memories;
 * - rests closed while the surface is Memories → Feed.
 *
 * Edit is left alone: Memories never opens over edit mode.
 */
internal fun reconcileMemoriesSurface(surface: HomeSurface, restedAt: Float): HomeSurface? = when {
    restedAt <= 0f && surface == HomeSurface.Feed -> HomeSurface.Memories
    restedAt >= 1f && surface == HomeSurface.Memories -> HomeSurface.Feed
    else -> null
}
