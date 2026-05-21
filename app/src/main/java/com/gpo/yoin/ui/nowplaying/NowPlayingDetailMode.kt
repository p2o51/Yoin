package com.gpo.yoin.ui.nowplaying

/**
 * Primary visual stage for Now Playing. The shell still owns the overlay;
 * these stages only describe how the overlay content reshapes in place.
 */
enum class NowPlayingStageMode { Compact, Expanded, Immersive }

/**
 * Page selection shared by the compact preview and expanded detail layout.
 * Ordinal order drives pager indices.
 */
enum class NowPlayingDetailPage { Lyrics, About, Note }
