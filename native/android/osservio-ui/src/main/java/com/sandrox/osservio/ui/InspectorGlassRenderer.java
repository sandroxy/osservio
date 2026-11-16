// Derived from Liquid-Glass-Android v2.0.11, commit fc6bd34fa3290638c3594cc39386f549d1316ab5.
// Copyright (c) 2025-2026 pandadog. MIT; see META-INF/osservio/liquid-glass-LICENSE.txt.
package com.sandrox.osservio.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.View;

/** API-isolated, single-surface inward lens. No bitmap capture or software/JNI fallback. */
@androidx.annotation.RequiresApi(33)
final class InspectorGlassRenderer implements InspectorGlassBar.Renderer {
    private final RuntimeShader shader = new RuntimeShader(InspectorGlassShader.SOURCE);
    private final RenderNode node = new RenderNode("OsservioNavigationMaterial");
    private final int[] location = new int[2], origin = new int[2];
    private int width, height, tint;
    private float radius, density;

    // The consumer rules preserve this factory boundary against R8 inlining into API-23 code.
    static InspectorGlassBar.Renderer create() { return new InspectorGlassRenderer(); }

    @Override public void draw(Canvas canvas, View source, View target, float cornerRadius, int color) {
        int w = target.getWidth(), h = target.getHeight();
        float scale = target.getResources().getDisplayMetrics().density;
        if (w != width || h != height || color != tint || cornerRadius != radius || scale != density) {
            configure(w, h, cornerRadius, color, scale);
            width = w; height = h; radius = cornerRadius; tint = color; density = scale;
        }
        target.getLocationOnScreen(location); source.getLocationOnScreen(origin);
        int margin = InspectorGlassBudget.MARGIN;
        int recordedWidth = w + margin * 2, recordedHeight = h + margin * 2;
        node.setPosition(-margin, -margin, w + margin, h + margin);
        Canvas recording = node.beginRecording(recordedWidth, recordedHeight);
        try {
            recording.translate(margin - (location[0] - origin[0]) - source.getScrollX(),
                margin - (location[1] - origin[1]) - source.getScrollY());
            source.draw(recording);
        } finally { node.endRecording(); }
        canvas.drawRenderNode(node);
    }

    private void configure(int width, int height, float radius, int tint, float density) {
        float w = width, h = height, shortSide = Math.min(w, h);
        float corner = Math.min(radius, shortSide / 2f);
        float margin = InspectorGlassBudget.MARGIN;
        // Effective CLEAR defaults from LiquidGlassView.tryDrawLensGlass. Keep geometry in pixels.
        shader.setFloatUniform("margin", margin);
        shader.setFloatUniform("viewSize", w, h);
        shader.setFloatUniform("shape1", w / 2, h / 2, w / 2, h / 2);
        shader.setFloatUniform("shape1L", w / 2, h / 2, w / 2, h / 2);
        shader.setFloatUniform("radii1", corner, corner, corner, corner);
        shader.setFloatUniform("rimSoft", 0);
        shader.setFloatUniform("shape2", 0, 0, 0, 0);
        shader.setFloatUniform("radius2", 0); shader.setFloatUniform("blendK", 0);
        shader.setFloatUniform("bevel", Math.max(2f, Math.min(16f * density, shortSide * .3f)));
        float refractionCap = .7f * shortSide * Math.min(1f, shortSide / (110f * density));
        shader.setFloatUniform("refractPx", Math.min(32f * density, refractionCap));
        shader.setFloatUniform("falloff", 2); shader.setFloatUniform("refractDir", -1);
        shader.setFloatUniform("sampleLo", margin + 1, margin + 1);
        shader.setFloatUniform("sampleHi", margin + w - 1, margin + h - 1);
        shader.setFloatUniform("dispersion", .1f);
        shader.setFloatUniform("lightDir", .866f, .5f);
        shader.setFloatUniform("specStrength", 1.2f);
        shader.setFloatUniform("rimBandMax", Math.max(2f, Math.min(6f, shortSide * .05f)));
        color("tintColor", 0x14FFFFFF); color("glassTint", tint);
        shader.setFloatUniform("adaptiveTint", 0); shader.setFloatUniform("dimAmount", .16f);
        shader.setFloatUniform("satFactor", 1.4f);
        shader.setFloatUniform("press", 0); shader.setFloatUniform("touchPos", 0, 0); shader.setFloatUniform("touchAmp", 0);
        float blur = ((int) ((6f * .35f) * 2)) / 2f;
        node.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"),
            RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)));
    }
    private void color(String name, int color) {
        shader.setFloatUniform(name, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, Color.alpha(color) / 255f);
    }
    @Override public void release() { node.setRenderEffect(null); node.discardDisplayList(); }
}
