#include <metal_stdlib>
#include <SwiftUI/SwiftUI_Metal.h>
using namespace metal;

// Android DotDissolve (ui/nowplaying/DotDissolve.kt): the content is masked by
// a grid of dots `cell` apart whose radius is `radius` (both in points); a
// pixel survives where it lies inside its cell's dot. Half a point of edge
// keeps the dots round at any scale.
[[ stitchable ]] half4 yoinDotDissolve(float2 position, half4 color, float cell, float radius) {
    float2 local = fmod(position, cell) - cell * 0.5;
    float d = length(local);
    half keep = half(1.0 - smoothstep(radius - 0.5, radius + 0.5, d));
    return color * keep;
}

// Focus's lyric halftone on ONE grid for a whole list (Android lyricHalftone):
// `origin` is this view's top-left in the list's content space, so every line's
// dots share the list's grid and line up with their neighbours; the grid rides
// with the content as it scrolls.
[[ stitchable ]] half4 yoinDotGrid(float2 position, half4 color, float cell, float radius, float2 origin) {
    float2 p = position + origin;
    float2 local = p - cell * floor(p / cell) - cell * 0.5;
    float d = length(local);
    half keep = half(1.0 - smoothstep(radius - 0.5, radius + 0.5, d));
    return color * keep;
}
