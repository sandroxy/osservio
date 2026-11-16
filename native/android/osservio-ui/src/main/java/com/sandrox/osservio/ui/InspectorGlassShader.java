// Derived from Liquid-Glass-Android v2.0.11, commit fc6bd34fa3290638c3594cc39386f549d1316ab5.
// Copyright (c) 2025-2026 pandadog. MIT; see META-INF/osservio/liquid-glass-LICENSE.txt.
package com.sandrox.osservio.ui;

/** Upstream optical equations; comments and string packaging do not alter the AGSL tokens. */
final class InspectorGlassShader {
    private InspectorGlassShader() { }
    static final String SOURCE =
        "uniform shader content;\n" +
        "uniform float  margin;\n" +
        "uniform float2 viewSize;\n" +
        "uniform float4 shape1;\n" +
        "uniform float4 radii1;\n" +
        "uniform float4 shape1L;\n" +
        "uniform float  rimSoft;\n" +
        "uniform float4 shape2;\n" +
        "uniform float  radius2;\n" +
        "uniform float  blendK;\n" +
        "uniform float  bevel;\n" +
        "uniform float  refractPx;\n" +
        "uniform float  falloff;\n" +
        "uniform float  refractDir;\n" +
        "uniform float2 sampleLo;\n" +
        "uniform float2 sampleHi;\n" +
        "uniform float  dispersion;\n" +
        "uniform float2 lightDir;\n" +
        "uniform float  specStrength;\n" +
        "uniform float  rimBandMax;\n" +
        "uniform float4 tintColor;\n" +
        "uniform float  adaptiveTint;\n" +
        "uniform float4 glassTint;\n" +
        "uniform float  dimAmount;\n" +
        "uniform float  satFactor;\n" +
        "uniform float  press;\n" +
        "uniform float2 touchPos;\n" +
        "uniform float  touchAmp;\n" +
        "float sdRoundedBox(float2 p, float2 b, float r) {\n" +
        "float2 q = abs(p) - b + r;\n" +
        "return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n" +
        "}\n" +
        "float sdRoundedBox4(float2 p, float2 b, float4 r) {\n" +
        "float rx = (p.x > 0.0) ? ((p.y > 0.0) ? r.z : r.y) : ((p.y > 0.0) ? r.w : r.x);\n" +
        "float2 q = abs(p) - b + rx;\n" +
        "return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rx;\n" +
        "}\n" +
        "float sminPoly(float a, float b, float k) {\n" +
        "float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);\n" +
        "return mix(b, a, h) - k * h * (1.0 - h);\n" +
        "}\n" +
        "float lensSDF(float2 p) {\n" +
        "float d = sdRoundedBox4(p - shape1L.xy, shape1L.zw, radii1);\n" +
        "if (shape2.z > 0.5) {\n" +
        "float d2 = sdRoundedBox(p - shape2.xy, shape2.zw, radius2);\n" +
        "if (blendK > 0.5) {\n" +
        "d = sminPoly(d, d2, blendK);\n" +
        "} else {\n" +
        "d = min(d, d2);\n" +
        "}\n" +
        "}\n" +
        "return d;\n" +
        "}\n" +
        "half4 main(float2 coord) {\n" +
        "float2 p = coord - float2(margin, margin);\n" +
        "if (p.x < 0.0 || p.y < 0.0 || p.x > viewSize.x || p.y > viewSize.y) {\n" +
        "return half4(0.0);\n" +
        "}\n" +
        "float d = lensSDF(p);\n" +
        "float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);\n" +
        "if (cov <= 0.004) {\n" +
        "return half4(0.0);\n" +
        "}\n" +
        "float2 n = float2(\n" +
        "lensSDF(p + float2(1.0, 0.0)) - lensSDF(p - float2(1.0, 0.0)),\n" +
        "lensSDF(p + float2(0.0, 1.0)) - lensSDF(p - float2(0.0, 1.0))\n" +
        ");\n" +
        "float nLen = length(n);\n" +
        "if (nLen > 0.0001) {\n" +
        "n = n / nLen;\n" +
        "} else {\n" +
        "n = float2(0.0, -1.0);\n" +
        "}\n" +
        "float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);\n" +
        "float edge = 1.0 - t;\n" +
        "float slope;\n" +
        "if (falloff > 0.001) {\n" +
        "float gB = pow(5.0, -falloff);\n" +
        "slope = (pow(1.0 + 4.0 * t, -falloff) - gB) / (1.0 - gB);\n" +
        "} else {\n" +
        "slope = edge * edge;\n" +
        "}\n" +
        "float refr = refractPx * (1.0 + 0.6 * press);\n" +
        "float2 offset = n * (refractDir * slope * refr);\n" +
        "if (touchAmp > 0.001) {\n" +
        "float2 tp = p - touchPos;\n" +
        "float tr = length(tp);\n" +
        "float sigma = max(max(shape1.z, shape1.w), 1.0);\n" +
        "float bump = touchAmp * exp(-(tr * tr) / (sigma * sigma * 0.30));\n" +
        "if (tr > 1.0) {\n" +
        "offset -= (tp / tr) * (bump * refr * 0.5);\n" +
        "}\n" +
        "}\n" +
        "float2 cR = coord + offset * (1.0 - dispersion * slope);\n" +
        "float2 cG = coord + offset;\n" +
        "float2 cB = coord + offset * (1.0 + dispersion * slope);\n" +
        "float2 lo = sampleLo;\n" +
        "float2 hi = sampleHi;\n" +
        "cR = clamp(cR, lo, hi);\n" +
        "cG = clamp(cG, lo, hi);\n" +
        "cB = clamp(cB, lo, hi);\n" +
        "float3 col;\n" +
        "if (rimSoft > 0.01 && slope > 0.001) {\n" +
        "float2 sm = n * (rimSoft * slope);\n" +
        "float2 cR1 = clamp(cR - sm, lo, hi);\n" +
        "float2 cR2 = clamp(cR + sm, lo, hi);\n" +
        "float2 cG1 = clamp(cG - sm, lo, hi);\n" +
        "float2 cG2 = clamp(cG + sm, lo, hi);\n" +
        "float2 cB1 = clamp(cB - sm, lo, hi);\n" +
        "float2 cB2 = clamp(cB + sm, lo, hi);\n" +
        "col = float3(\n" +
        "(content.eval(cR).r + content.eval(cR1).r + content.eval(cR2).r) / 3.0,\n" +
        "(content.eval(cG).g + content.eval(cG1).g + content.eval(cG2).g) / 3.0,\n" +
        "(content.eval(cB).b + content.eval(cB1).b + content.eval(cB2).b) / 3.0\n" +
        ");\n" +
        "} else {\n" +
        "col = float3(\n" +
        "content.eval(cR).r,\n" +
        "content.eval(cG).g,\n" +
        "content.eval(cB).b\n" +
        ");\n" +
        "}\n" +
        "float lum = dot(col, float3(0.2126, 0.7152, 0.0722));\n" +
        "if (satFactor <= 1.0) {\n" +
        "col = mix(float3(lum), col, satFactor);\n" +
        "} else {\n" +
        "float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));\n" +
        "float room = 1.0 - smoothstep(0.2, 0.85, satNow);\n" +
        "float hl = 1.0 - smoothstep(0.75, 0.98, lum);\n" +
        "float amount = 1.0 + (satFactor - 1.0) * mix(0.3, 1.0, room * hl);\n" +
        "col = clamp(mix(float3(lum), col, amount), float3(0.0), float3(1.0));\n" +
        "}\n" +
        "if (adaptiveTint > 0.5) {\n" +
        "float lumT = dot(col, float3(0.2126, 0.7152, 0.0722));\n" +
        "float e = smoothstep(0.35, 0.75, lumT);\n" +
        "col = mix(col, float3(1.0 - e), 0.14 + 0.08 * e);\n" +
        "} else {\n" +
        "col = mix(col, tintColor.rgb, tintColor.a);\n" +
        "}\n" +
        "col = col * (1.0 - dimAmount);\n" +
        "if (glassTint.a > 0.002) {\n" +
        "float lumTint = dot(col, float3(0.2126, 0.7152, 0.0722));\n" +
        "float3 absorbed = col * mix(float3(1.0), glassTint.rgb, 0.85);\n" +
        "float3 scattered = glassTint.rgb * (0.38 * (1.0 - lumTint));\n" +
        "col = mix(col, clamp(absorbed + scattered, float3(0.0), float3(1.0)), glassTint.a);\n" +
        "}\n" +
        "float facing = dot(n, -lightDir);\n" +
        "float lobeF = pow(max(facing, 0.0), 4.5);\n" +
        "float lobeB = pow(max(-facing, 0.0), 4.5);\n" +
        "float bandW = clamp(bevel * 0.3, 2.0, rimBandMax);\n" +
        "float glowIn = clamp((-d - 1.0) / 2.0, 0.0, 1.0);\n" +
        "float glow = glowIn * pow(clamp(1.0 - (-d - 3.0) / bandW, 0.0, 1.0), 1.5) * cov;\n" +
        "float hair = clamp(1.0 - abs(d + 1.0) / 2.0, 0.0, 1.0) * cov;\n" +
        "float spec = (hair * 0.70 * (lobeF + lobeB) + glow * 0.10 * lobeF)\n" +
        "* specStrength * (1.0 - 0.35 * press);\n" +
        "col += float3(spec);\n" +
        "col = clamp(col, float3(0.0), float3(1.0));\n" +
        "return half4(half3(col * cov), half(cov));\n" +
        "}\n";
}
