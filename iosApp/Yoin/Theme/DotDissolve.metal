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

// Focus's edge dots (Android EdgeDotBand.kt drawEdgeBand), per pixel: one
// fixed hex lattice `5.4` apart whose dots only change SIZE — biggest at the
// screen edge, shrinking to nothing inward; slow waves travel round the
// border. Lengths are points. Over a faint wash of the cover's colours.
//
// size: the view; phase: the waves' clock (s); focus 0..1; burst/fade: the
// band's life (edgeBandLife); playing 0..1; cover, lyrics: where it grows out
// from and where a line swell starts; lineLife / songLife: seconds since the
// last line / song pulse (negative = none); dark 0/1; ink0..3: the dots'
// colours by angle; wash0..3: bottom, top, left, right wash colours.
static float edgeBandRadius(float2 p, float2 size, float phase, float focus, float burst, float fade,
                            float playing, float2 cover, float2 lyrics, float lineLife, float songLife,
                            float bandWidth, float rMax, float corner, float limit) {
    float w = size.x, h = size.y;
    float ex = min(p.x, w - p.x);
    float ey = min(p.y, h - p.y);
    if (ex > limit && ey > limit) return 0.0;
    float e = min(ex, ey);
    if (ex < corner && ey < corner) e = corner - length(float2(corner - ex, corner - ey));
    float th = atan2(p.y - h * 0.5, p.x - w * 0.5);
    float wave = clamp(0.5 + 0.36 * sin(2.0 * th - phase * 0.35) + 0.16 * sin(5.0 * th + phase * 0.25 + 1.1), 0.08, 1.0);
    // Entry: grows out from the cover's side with a small overshoot.
    float ek = clamp(focus * 1.8 - length(p - cover) / length(size) * 0.8, 0.0, 1.0);
    float q = ek - 1.0;
    float grow = 1.0 + 2.1 * q * q * q + 1.1 * q * q;
    float swell = 1.0;
    if (lineLife >= 0.0 && lineLife <= 1.6) {
        float amp = 0.38 * exp(-lineLife * 1.6);
        float dd = length(p - lyrics) - lineLife * 620.0;
        swell += amp * exp(-dd * dd / 5000.0);
    }
    if (songLife >= 0.0 && songLife <= 1.4) {
        float amp = 0.55 * sin(M_PI_F * songLife / 1.4);
        float dx = p.x - songLife * w * 0.9;
        swell += amp * exp(-dx * dx / 9000.0);
    }
    float play = 0.78 + 0.22 * playing;
    float reach = max(1.0, bandWidth * wave * grow * play * swell);
    float d = 1.0 - e / reach;
    if (d <= 0.0) return 0.0;
    d = d * d * (3.0 - 2.0 * d);
    float r = rMax * d;
    return r >= 0.25 ? r : 0.0;
}

static half4 over(half4 top, half4 under) { return top + under * (1.0h - top.a); }

[[ stitchable ]] half4 yoinEdgeBand(float2 position, half4 color, float2 size, float phase, float focus,
                                    float burst, float fade, float playing, float2 cover, float2 lyrics,
                                    float lineLife, float songLife, float dark,
                                    half4 ink0, half4 ink1, half4 ink2, half4 ink3,
                                    half4 wash0, half4 wash1, half4 wash2, half4 wash3) {
    float w = size.x, h = size.y;
    float shortSide = min(w, h);
    float bandWidth = shortSide * 0.15 * (1.0 + 1.4 * burst) * (0.25 + 0.75 * fade);
    float rMax = 1.2 * (1.0 + 0.4 * burst);
    float corner = shortSide * 0.09;
    float limit = bandWidth * 1.7;

    // The faint wash under the band, so the dots sit on light.
    float washAlpha = (dark > 0.5 ? 0.22 : 0.3) * focus * fade;
    float washDepth = max(1.0, bandWidth * 1.1);
    float2 p = position;
    half4 out = half4(0.0h);
    out = over(wash1 * half(washAlpha * clamp(1.0 - p.y / washDepth, 0.0, 1.0)), out);
    out = over(wash0 * half(washAlpha * clamp(1.0 - (h - p.y) / washDepth, 0.0, 1.0)), out);
    out = over(wash2 * half(washAlpha * clamp(1.0 - p.x / washDepth, 0.0, 1.0)), out);
    out = over(wash3 * half(washAlpha * clamp(1.0 - (w - p.x) / washDepth, 0.0, 1.0)), out);

    // The nearest lattice dot: rows `pitch·0.866` apart, odd rows offset by half.
    float pitch = 5.4;
    float rowStep = pitch * 0.866;
    float j0 = floor(p.y / rowStep);
    float best = 1e9;
    float2 dot = float2(0.0);
    for (int k = 0; k < 2; k++) {
        float j = j0 + float(k);
        float off = fmod(j, 2.0) > 0.5 ? pitch * 0.5 : 0.0;
        float i = round((p.x - off) / pitch);
        float2 c = float2(i * pitch + off, j * rowStep);
        float dd = length(p - c);
        if (dd < best) { best = dd; dot = c; }
    }
    if (dot.x < 0.0 || dot.x > w || dot.y > h) return out;
    float r = edgeBandRadius(dot, size, phase, focus, burst, fade, playing, cover, lyrics, lineLife, songLife,
                             bandWidth, rMax, corner, limit);
    if (r <= 0.0) return out;
    float cover01 = 1.0 - smoothstep(r - 0.4, r + 0.4, best);
    if (cover01 <= 0.0) return out;
    // Twelve colour segments round the screen, very slowly rotating.
    float th = atan2(dot.y - h * 0.5, dot.x - w * 0.5);
    float seg = floor(((th / (2.0 * M_PI_F) + 0.5) + phase * 0.004) * 12.0);
    seg = fmod(fmod(seg, 12.0) + 12.0, 12.0);
    float u = seg / 12.0 * 4.0;
    int a = int(floor(u)) % 4;
    float f = u - floor(u);
    half4 inks[4] = { ink0, ink1, ink2, ink3 };
    half4 ink = mix(inks[a], inks[(a + 1) % 4], half(f));
    float alpha = (dark > 0.5 ? 0.55 : 0.45) * focus * min(1.0, fade * 1.4) * cover01;
    // Inks arrive opaque (premultiplied = straight); scale to the dot's alpha.
    return over(half4(ink.rgb, 1.0h) * half(alpha), out);
}
