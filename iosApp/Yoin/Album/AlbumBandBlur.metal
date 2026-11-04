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
