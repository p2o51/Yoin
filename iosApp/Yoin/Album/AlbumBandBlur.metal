#include <metal_stdlib>
#include <SwiftUI/SwiftUI_Metal.h>
using namespace metal;

// The album spectrum's soft lower edge (AlbumDetailView `bandBlur`): a
// variable blur for a track-list row passing under the band. The radius
// follows the band's own fade curve, the same smoothstep in reverse: full
// `maxRadius` at the band's solid edge (`zoneTop`, in the row's own points),
// none `zoneHeight` below it. A Vogel disc of 24 Gaussian-weighted taps; a
// pixel below the zone returns its own color untouched.
[[ stitchable ]] half4 albumBandBlur(float2 position, SwiftUI::Layer layer,
                                     float zoneTop, float zoneHeight, float maxRadius) {
    float t = clamp((position.y - zoneTop) / zoneHeight, 0.0, 1.0);
    float r = maxRadius * (1.0 - smoothstep(0.0, 1.0, t));
    if (r < 0.35) {
        return layer.sample(position);
    }
    const int taps = 24;
    const float golden = 2.39996323;
    half4 sum = layer.sample(position);
    float total = 1.0;
    for (int i = 1; i < taps; i++) {
        float k = float(i) / float(taps);
        float d = sqrt(k) * r;
        float a = float(i) * golden;
        float w = exp(-2.0 * k);
        sum += layer.sample(position + float2(cos(a), sin(a)) * d) * half(w);
        total += w;
    }
    return sum / half(total);
}

// The album Strip's progressive blur (AlbumDetailView `spectrum`, 51
// 2026-11-06): none at the screen's top edge, growing along a smoothstep to
// `maxRadius` at `zoneHeight` (the end of the feather) and beyond. The
// strips are vertical bands of one color each, so the blur runs along x
// only: 33 evenly spaced Gaussian taps, x clamped inside the layer, which
// smears neighbouring colors into one another with no sparse-disc grain and
// never reads the clear space past the strips (they are drawn past the
// feather's end; the mask does the fading).
[[ stitchable ]] half4 albumStripBlur(float2 position, SwiftUI::Layer layer,
                                      float zoneHeight, float maxRadius, float width) {
    float t = clamp(position.y / zoneHeight, 0.0, 1.0);
    float r = maxRadius * smoothstep(0.0, 1.0, t);
    if (r < 0.35) {
        return layer.sample(position);
    }
    const int half_taps = 16;
    half4 sum = half4(0.0);
    float total = 0.0;
    for (int i = -half_taps; i <= half_taps; i++) {
        float k = float(i) / float(half_taps);
        float w = exp(-4.5 * k * k);
        float x = clamp(position.x + k * r * 1.5, 0.5, width - 0.5);
        sum += layer.sample(float2(x, position.y)) * half(w);
        total += w;
    }
    return sum / half(total);
}
