package com.gpo.yoin.ui.theme

import androidx.compose.ui.text.TextStyle

internal fun TextStyle.withTabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")
