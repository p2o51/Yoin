package com.gpo.yoin.ui.navigation.back

sealed interface BackSurfaceKind {
    data object RootSection : BackSurfaceKind

    data object PushPage : BackSurfaceKind

    data object ShellOverlayDown : BackSurfaceKind

    data object ShellOverlayUp : BackSurfaceKind
}
