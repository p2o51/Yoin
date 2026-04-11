package com.gpo.yoin.ui.component;

import androidx.compose.ui.graphics.Path;
import androidx.graphics.shapes.Morph;

public final class MaterialShapeInterop {
    private MaterialShapeInterop() {}

    public static Path morphToPath(Morph morph, float progress, Path path, int startAngle) {
        return androidx.compose.material3.MaterialShapesKt.toPath(morph, progress, path, startAngle);
    }
}
