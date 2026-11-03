#include <metal_stdlib>
#include <SwiftUI/SwiftUI_Metal.h>
using namespace metal;

// Static film grain for the experimental "Yoin glass" chrome.
[[ stitchable ]] half4 yoinGrain(float2 position, half4 color, float strength) {
    float2 cell = floor(position * 0.75);
    float n = fract(sin(dot(cell, float2(12.9898, 78.233))) * 43758.5453);
    half v = half(mix(0.5, n, strength));
    return half4(v, v, v, 1.0h) * color.a;
}
