package com.gpo.yoin.ui.nowplaying

/**
 * Secondary UI mode for Now Playing. Compact is today's playback surface;
 * Fullscreen is the Lyrics/About/Note detail surface opened by tapping the
 * compact pager or the Write pill.
 *
 * This state is hoisted above [NowPlayingScreen] — `YoinNavHost` owns it so
 * the system back handler can close Fullscreen before it closes Now Playing.
 */
enum class NowPlayingDetailMode { Compact, Fullscreen }

/**
 * Page selection shared across Compact and Fullscreen — switching tabs in
 * one propagates to the other. Ordinal order drives pager indices.
 */
enum class NowPlayingDetailPage { Lyrics, About, Note }
