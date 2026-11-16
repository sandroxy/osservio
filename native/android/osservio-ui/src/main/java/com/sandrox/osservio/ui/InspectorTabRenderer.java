// Optical programs and layer recipe: Copyright 2025 Kyant, Apache-2.0.
// Native rendering changes: see META-INF/osservio/backdrop-NOTICE.txt.
package com.sandrox.osservio.ui;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import androidx.annotation.DoNotInline;
import androidx.annotation.RequiresApi;
import java.util.ArrayList;
import java.util.List;

/** Hardware-only optical layers. Its owner admits geometry before constructing any shaders. */
@RequiresApi(33)
final class InspectorTabRenderer implements InspectorTabBar.Renderer {
    private static final ColorMatrixColorFilter VIBRANCY = new ColorMatrixColorFilter(new float[] {
        1.3935f, -.3575f, -.036f, 0, 0, -.1065f, 1.1425f, -.036f, 0, 0,
        -.1065f, -.3575f, 1.464f, 0, 0, 0, 0, 0, 1, 0
    });
    private final List<RenderNode> nodes = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint erase = new Paint(Paint.ANTI_ALIAS_FLAG), plus = new Paint();
    private final Path clip = new Path();
    private RenderNode source, ink;
    private Surface base, selectedInk, lens;
    private long sourceVersion = Long.MIN_VALUE, inkVersion = Long.MIN_VALUE;
    private float inkPress = -1, inkOffset, inkPosition, inkGlow;
    private int sourceWidth, sourceHeight;

    @DoNotInline static InspectorTabBar.Renderer create() { return new InspectorTabRenderer(); }
    private InspectorTabRenderer() { erase.setBlendMode(BlendMode.CLEAR); plus.setBlendMode(BlendMode.PLUS); }
    private RenderNode node(String name) {
        RenderNode result = new RenderNode(name); result.setClipToBounds(false); nodes.add(result); return result;
    }
    @Override public void draw(Canvas canvas, InspectorTabBar bar) {
        float width = bar.getWidth(), height = bar.getHeight(), density = bar.density;
        float inkHeight = height - bar.dp(8), progress = bar.motion.progress();
        if (source == null) {
            source = node("Navigation source"); ink = node("Navigation selected labels");
            base = new Surface("Navigation base", false);
            selectedInk = new Surface("Navigation labels", false);
            lens = new Surface("Navigation lens", true);
        }
        // Retain the overflow used by the enlarged control without wrapping or reparenting pages.
        int overflowX = (int) Math.ceil(bar.dp(12)), overflowY = (int) Math.ceil(bar.dp(14));
        int widthWithOverflow = (int) Math.ceil(width + bar.dp(24));
        int heightWithOverflow = (int) Math.ceil(height + bar.dp(28));
        boolean sourceChanged = sourceVersion != bar.sourceVersion() || sourceWidth != widthWithOverflow || sourceHeight != heightWithOverflow;
        if (sourceChanged) {
            sourceWidth = widthWithOverflow; sourceHeight = heightWithOverflow;
            int margin = InspectorTabGeometry.SOURCE_MARGIN;
            source.setPosition(-overflowX - margin, -overflowY - margin,
                sourceWidth - overflowX + margin, sourceHeight - overflowY + margin);
            Canvas content = source.beginRecording(sourceWidth + 2 * margin, sourceHeight + 2 * margin);
            try { content.translate(overflowX + margin, overflowY + margin); bar.drawSource(content); }
            finally { source.endRecording(); }
            sourceVersion = bar.sourceVersion();
        }
        float offset = bar.panelOffset(), baseScale = 1 + bar.dp(16) / width * progress;
        base.draw(canvas, bar, offset, 0, width, height, baseScale, baseScale,
            8 * density, 24 * density, 24 * density, 1, 1, 0, false, false);
        if (sourceChanged || inkVersion != bar.inkVersion() || inkPress != progress || inkOffset != offset
            || inkPosition != bar.visualPosition() || inkGlow != (float) bar.motion.glow.value) {
            ink.setPosition(-overflowX, -overflowY, sourceWidth - overflowX, sourceHeight - overflowY);
            Canvas content = ink.beginRecording(sourceWidth, sourceHeight);
            try {
                content.translate(overflowX, overflowY);
                selectedInk.draw(content, bar, offset, bar.dp(4), width, inkHeight, 1, 1,
                    8 * density, 24 * density * progress, 24 * density * progress, progress, 1, 0, true, false);
            } finally { ink.endRecording(); }
            inkVersion = bar.inkVersion(); inkPress = progress; inkOffset = offset;
            inkPosition = bar.visualPosition(); inkGlow = (float) bar.motion.glow.value;
        }
        lens.draw(canvas, bar, bar.lensX(), bar.dp(4), bar.tabWidth(), inkHeight,
            bar.lensScaleX(), bar.lensScaleY(), 0, 10 * density * progress, 14 * density * progress,
            progress, progress, 8 * density * progress, false, true);
    }
    private void roundClip(Canvas canvas, float width, float height) {
        clip.reset(); clip.addRoundRect(0, 0, width, height, height / 2, height / 2, Path.Direction.CW); canvas.clipPath(clip);
    }
    private final class Surface {
        final RenderNode image, outer, inner, highlight;
        final RuntimeShader refraction, highlightShader, glowShader;
        final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float oldWidth = -1, oldHeight = -1, oldBlur = -1, oldBand = -1, oldAmount = -1;
        float outerBlur = -1, innerBlur = -1, highlightBlur = -1;
        int oldPadding = -1;

        Surface(String name, boolean dispersion) {
            image = node(name + " backdrop"); outer = node(name + " shadow");
            inner = node(name + " inner shadow"); highlight = node(name + " highlight");
            refraction = new RuntimeShader(dispersion ? InspectorTabShaders.DISPERSION : InspectorTabShaders.REFRACTION);
            highlightShader = new RuntimeShader(InspectorTabShaders.HIGHLIGHT);
            glowShader = new RuntimeShader(InspectorTabShaders.INTERACTIVE);
            if (dispersion) refraction.setFloatUniform("chromaticAberration", 1);
        }
        void configure(float width, float height, float blur, float band, float amount, int padding) {
            if (width == oldWidth && height == oldHeight && blur == oldBlur && band == oldBand && amount == oldAmount && padding == oldPadding) return;
            RenderEffect effect = null;
            if (blur > 0) {
                effect = RenderEffect.createColorFilterEffect(VIBRANCY);
                effect = RenderEffect.createBlurEffect(blur, blur, effect, Shader.TileMode.CLAMP);
            }
            if (band > 0 && amount > 0) {
                refraction.setFloatUniform("size", width, height); refraction.setFloatUniform("offset", -padding, -padding);
                refraction.setFloatUniform("cornerRadii", height / 2, height / 2, height / 2, height / 2);
                refraction.setFloatUniform("refractionHeight", band); refraction.setFloatUniform("refractionAmount", -amount);
                refraction.setFloatUniform("depthEffect", 0);
                RenderEffect optical = RenderEffect.createRuntimeShaderEffect(refraction, "content");
                effect = effect == null ? optical : RenderEffect.createChainEffect(optical, effect);
            }
            image.setRenderEffect(effect); oldWidth = width; oldHeight = height; oldBlur = blur;
            oldBand = band; oldAmount = amount; oldPadding = padding;
        }
        void draw(Canvas target, InspectorTabBar bar, float x, float y, float width, float height,
                  float scaleX, float scaleY, float blur, float band, float amount,
                  float highlightAlpha, float shadowAlpha, float innerRadius, boolean colored, boolean selector) {
            float density = bar.density, progress = bar.motion.progress();
            int padding = (int) Math.ceil(Math.max(0, blur - band));
            configure(width, height, blur, band, amount, padding);
            image.setPosition(-padding, -padding, (int) Math.ceil(width) + padding, (int) Math.ceil(height) + padding);
            Canvas recorded = image.beginRecording((int) Math.ceil(width) + 2 * padding, (int) Math.ceil(height) + 2 * padding);
            try {
                recorded.translate(padding, padding); recorded.scale(1 / scaleX, 1 / scaleY);
                recorded.translate(-(x + (1 - scaleX) * width / 2), -(y + (1 - scaleY) * height / 2));
                recorded.drawRenderNode(source); if (selector) recorded.drawRenderNode(ink);
            } finally { image.endRecording(); }
            int save = target.save(); target.translate(x, y); target.scale(scaleX, scaleY, width / 2, height / 2);
            drawOuter(target, width, height, 24 * density, 4 * density, shadowAlpha);
            int body = target.save(); roundClip(target, width, height); target.drawRenderNode(image);
            paint.setShader(null); paint.setBlendMode(BlendMode.SRC_OVER); paint.setStyle(Paint.Style.FILL);
            if (selector) {
                paint.setColor(bar.dark() ? Color.WHITE : Color.BLACK); paint.setAlpha(Math.round(255 * .1f * (1 - progress)));
                target.drawRect(0, 0, width, height, paint);
                paint.setColor(Color.BLACK); paint.setAlpha(Math.round(255 * .03f * progress)); target.drawRect(0, 0, width, height, paint);
            } else {
                paint.setColor(bar.dark() ? 0xFF121212 : 0xFFFAFAFA); paint.setAlpha(102); target.drawRect(0, 0, width, height, paint);
                drawGlow(target, bar, width, height);
                bar.drawLabels(target, width, height, colored, colored ? 1 + .2f * progress : 1);
            }
            target.restoreToCount(body);
            drawHighlight(target, width, height, density, highlightAlpha);
            if (innerRadius > 0) drawInner(target, width, height, innerRadius, progress);
            target.restoreToCount(save);
        }
        void drawOuter(Canvas canvas, float width, float height, float radius, float offset, float alpha) {
            if (alpha <= 0) return;
            int margin = (int) Math.ceil(radius * 2), w = (int) Math.ceil(width) + margin * 2, h = (int) Math.ceil(height + offset) + margin * 2;
            outer.setPosition(-margin, -margin, w - margin, h - margin);
            if (outerBlur != radius) { outer.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)); outerBlur = radius; }
            Canvas shadow = outer.beginRecording(w, h);
            try {
                shadow.translate(margin, margin); paint.setStyle(Paint.Style.FILL); paint.setShader(null);
                paint.setBlendMode(BlendMode.SRC_OVER); paint.setColor(Color.BLACK); paint.setAlpha(Math.round(25.5f * alpha));
                shadow.drawRoundRect(0, offset, width, height + offset, height / 2, height / 2, paint);
            } finally { outer.endRecording(); }
            // Blur the silhouette, then remove the unshifted interior.
            int outside = canvas.save(); clip.reset();
            clip.addRoundRect(0, 0, width, height, height / 2, height / 2, Path.Direction.CW);
            canvas.clipOutPath(clip); canvas.drawRenderNode(outer); canvas.restoreToCount(outside);
        }
        void drawHighlight(Canvas canvas, float width, float height, float density, float alpha) {
            if (alpha <= 0) return;
            highlightShader.setFloatUniform("size", width, height);
            highlightShader.setFloatUniform("cornerRadii", height / 2, height / 2, height / 2, height / 2);
            highlightShader.setColorUniform("color", Color.WHITE); highlightShader.setFloatUniform("angle", (float) Math.PI / 4);
            highlightShader.setFloatUniform("falloff", 1);
            highlightPaint.setShader(highlightShader); highlightPaint.setColor(Color.WHITE); highlightPaint.setAlpha(Math.round(127.5f * alpha));
            highlightPaint.setStyle(Paint.Style.STROKE); highlightPaint.setStrokeWidth((float) Math.ceil(.5f * density) * 2);
            highlight.setPosition(-1, -1, (int) Math.ceil(width) + 1, (int) Math.ceil(height) + 1);
            if (highlightBlur != density) {
                highlight.setRenderEffect(RenderEffect.createBlurEffect(.25f * density, .25f * density, Shader.TileMode.DECAL)); highlightBlur = density;
            }
            Canvas outline = highlight.beginRecording((int) Math.ceil(width) + 2, (int) Math.ceil(height) + 2);
            try { outline.translate(1, 1); roundClip(outline, width, height); outline.drawRoundRect(0, 0, width, height, height / 2, height / 2, highlightPaint); }
            finally { highlight.endRecording(); }
            int layer = canvas.saveLayer(0, 0, width, height, plus); canvas.drawRenderNode(highlight); canvas.restoreToCount(layer);
        }
        void drawInner(Canvas canvas, float width, float height, float radius, float alpha) {
            inner.setPosition(0, 0, (int) Math.ceil(width), (int) Math.ceil(height));
            if (innerBlur != radius) { inner.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)); innerBlur = radius; }
            Canvas shadow = inner.beginRecording((int) Math.ceil(width), (int) Math.ceil(height));
            try {
                roundClip(shadow, width, height); paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setBlendMode(BlendMode.SRC_OVER);
                paint.setColor(Color.BLACK); paint.setAlpha(Math.round(255 * .15f * alpha));
                shadow.drawRoundRect(0, 0, width, height, height / 2, height / 2, paint);
                shadow.drawRoundRect(0, radius, width, height + radius, height / 2, height / 2, erase);
            } finally { inner.endRecording(); }
            int save = canvas.save(); roundClip(canvas, width, height); canvas.drawRenderNode(inner); canvas.restoreToCount(save);
        }
        void drawGlow(Canvas canvas, InspectorTabBar bar, float width, float height) {
            float glow = (float) bar.motion.glow.value;
            if (glow <= 0) return;
            paint.setBlendMode(BlendMode.PLUS); paint.setColor(Color.WHITE); paint.setAlpha(Math.round(255 * .08f * glow));
            canvas.drawRect(0, 0, width, height, paint);
            // Keep the shader opaque: Paint alpha premultiplies the additive light correctly.
            float center = (bar.visualPosition() + .5f) * bar.tabWidth() + bar.panelOffset();
            glowShader.setFloatUniform("size", width, height); glowShader.setColorUniform("color", Color.WHITE);
            glowShader.setFloatUniform("radius", height * 1.5f); glowShader.setFloatUniform("position", Math.max(0, Math.min(width, center)), height / 2);
            paint.setShader(glowShader); paint.setAlpha(Math.round(255 * .15f * Math.max(0, Math.min(1, glow))));
            canvas.drawRect(0, 0, width, height, paint); paint.setShader(null); paint.setBlendMode(BlendMode.SRC_OVER);
        }
    }
    @Override public void release() {
        for (RenderNode node : nodes) { node.setRenderEffect(null); node.discardDisplayList(); }
        nodes.clear(); source = null; ink = null; base = null; selectedInk = null; lens = null;
    }
}
