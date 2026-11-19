// Inward-lens program: Copyright (c) 2025-2026 pandadog, MIT.
// See META-INF/osservio/liquid-glass-LICENSE.txt and the vendored source record.
package com.sandrox.osservio.ui;

import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.view.View;
import androidx.annotation.DoNotInline;
import androidx.annotation.RequiresApi;

/** Local optical relief over a known opaque control backing. No page capture or self-sampling. */
@RequiresApi(33)
final class InspectorActionRenderer implements InspectorActionMaterial.Renderer {
    private final RuntimeShader shader = new RuntimeShader(InspectorGlassShader.SOURCE);
    private final RenderNode node = new RenderNode("Inspector control material");
    private int width, height, color;
    private float density, oldPress = -1;
    @DoNotInline static InspectorActionMaterial.Renderer create() { return new InspectorActionRenderer(); }
    @Override public void draw(Canvas canvas, View target, boolean prominent, float press) {
        int w = target.getWidth(), h = target.getHeight();
        float scale = target.getResources().getDisplayMetrics().density;
        int base = prominent ? InspectorPreferences.palette(target.getContext()).light : InspectorTheme.canvas(target.getContext());
        if (w != width || h != height || scale != density || base != color) {
            configure(w, h, scale); width = w; height = h; density = scale; color = base;
            int margin = InspectorGlassBudget.MARGIN;
            node.setPosition(-margin, -margin, width + margin, height + margin);
            Canvas source = node.beginRecording(width + margin * 2, height + margin * 2);
            try { source.drawColor(base); } finally { node.endRecording(); }
        }
        if (oldPress != press) {
            shader.setFloatUniform("press", press); oldPress = press;
            node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));
        }
        canvas.drawRenderNode(node);
    }
    private void configure(int width, int height, float density) {
        float w = width, h = height, side = Math.min(w, h), radius = side / 2;
        float margin = InspectorGlassBudget.MARGIN;
        shader.setFloatUniform("margin", margin); shader.setFloatUniform("viewSize", w, h);
        shader.setFloatUniform("shape1", w / 2, h / 2, w / 2, h / 2);
        shader.setFloatUniform("shape1L", w / 2, h / 2, w / 2, h / 2);
        shader.setFloatUniform("radii1", radius, radius, radius, radius); shader.setFloatUniform("rimSoft", 0);
        shader.setFloatUniform("shape2", 0, 0, 0, 0); shader.setFloatUniform("radius2", 0); shader.setFloatUniform("blendK", 0);
        shader.setFloatUniform("bevel", Math.max(2, Math.min(16 * density, side * .3f)));
        shader.setFloatUniform("refractPx", Math.min(32 * density, .7f * side * Math.min(1, side / (110 * density))));
        shader.setFloatUniform("falloff", 2); shader.setFloatUniform("refractDir", -1);
        shader.setFloatUniform("sampleLo", margin + 1, margin + 1); shader.setFloatUniform("sampleHi", margin + w - 1, margin + h - 1);
        shader.setFloatUniform("dispersion", .1f); shader.setFloatUniform("lightDir", .866f, .5f);
        shader.setFloatUniform("specStrength", 1); shader.setFloatUniform("rimBandMax", Math.max(2, Math.min(6, side * .05f)));
        shader.setFloatUniform("tintColor", 0, 0, 0, 0); shader.setFloatUniform("glassTint", 0, 0, 0, 0);
        shader.setFloatUniform("adaptiveTint", 0); shader.setFloatUniform("dimAmount", 0); shader.setFloatUniform("satFactor", 1);
        shader.setFloatUniform("touchPos", w / 2, h / 2); shader.setFloatUniform("touchAmp", 0);
        oldPress = -1;
    }
    @Override public void release() { node.setRenderEffect(null); node.discardDisplayList(); }
}
