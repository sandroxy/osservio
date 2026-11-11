package com.sandrox.osservio.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.annotation.SuppressLint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;
import java.util.Locale;

/** Overrides only SDK appearance and language; system configuration remains live. */
final class InspectorTheme {
    private InspectorTheme() { }
    static Context context(Context host) {
        boolean dark = InspectorPreferences.dark(host), english = InspectorPreferences.english(host);
        Context candidate = host;
        for (int i = 0; i < 16 && candidate instanceof android.content.ContextWrapper; i++) {
            if (candidate instanceof ScopedContext) {
                ScopedContext scoped = (ScopedContext) candidate;
                if (scoped.dark == dark && scoped.english == english) { scoped.updatePalette(); return host; }
                break;
            }
            Context base = ((android.content.ContextWrapper) candidate).getBaseContext();
            if (base == candidate) break; candidate = base;
        }
        if (host instanceof ScopedContext) {
            ScopedContext scoped = (ScopedContext) host;
            if (scoped.dark == dark && scoped.english == english) { scoped.updatePalette(); return host; }
            host = scoped.getBaseContext();
        }
        return new ScopedContext(host, dark, english);
    }
    private static final class ScopedContext extends ContextThemeWrapper {
        final boolean dark;
        final boolean english;
        private Resources localized;
        private InspectorPalette palette;
        // Both languages are retained in a generated, unqualified resource table.
        @SuppressLint("AppBundleLocaleChanges")
        ScopedContext(Context host, boolean dark, boolean english) {
            super(host, dark ? R.style.OsservioInspectorDark : R.style.OsservioInspectorLight);
            this.dark = dark; this.english = english;
            Configuration configuration = new Configuration();
            // Nested configuration contexts replace inherited overrides. Carry the
            // host's explicit sizing choices without pinning ordinary system values.
            Configuration inherited = host.getResources().getConfiguration();
            Configuration application = host.getApplicationContext().getResources().getConfiguration();
            if (inherited.fontScale != application.fontScale) configuration.fontScale = inherited.fontScale;
            if (inherited.densityDpi != application.densityDpi) configuration.densityDpi = inherited.densityDpi;
            configuration.setLocale(english ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE);
            configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            applyOverrideConfiguration(configuration); updatePalette();
        }
        private void updatePalette() {
            InspectorPalette selected = InspectorPreferences.palette(this);
            if (selected == palette) return;
            palette = selected; getTheme().applyStyle(selected.style, true);
        }
        @Override public Resources getResources() {
            if (localized == null) localized = new InspectorResources(super.getResources(), english);
            return localized;
        }
    }
    static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static int color(Context context, int resource) {
        if (resource == R.color.osservio_accent || resource == R.color.osservio_tint || resource == R.color.osservio_press) {
            InspectorPalette palette = InspectorPreferences.palette(context);
            boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            int accent = dark ? palette.dark : palette.light;
            if (resource == R.color.osservio_accent) return accent;
            if (resource == R.color.osservio_tint) return dark ? palette.darkTint : palette.lightTint;
            return (accent & 0xFFFFFF) | (dark ? 0x28000000 : 0x18000000);
        }
        return context.getResources().getColor(resource, context.getTheme());
    }
    static int canvas(Context context) { return color(context, R.color.osservio_canvas); }
    static int surface(Context context) { return color(context, R.color.osservio_surface); }
    static int primary(Context context) { return color(context, R.color.osservio_primary); }
    static int secondary(Context context) { return color(context, R.color.osservio_secondary); }
    static int accent(Context context) { return color(context, R.color.osservio_accent); }
    static GradientDrawable shape(Context context, int color, int radius, boolean border) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        if (border) shape.setStroke(dp(context, 1), color(context, R.color.osservio_separator));
        return shape;
    }
    static void surface(View view, boolean interactive, int radius) {
        Context context = view.getContext();
        GradientDrawable fill = shape(context, surface(context), radius, false);
        view.setBackground(interactive ? ripple(context, fill, null) : fill);
    }
    static RippleDrawable ripple(Context context, android.graphics.drawable.Drawable content, android.graphics.drawable.Drawable mask) {
        return new RippleDrawable(ColorStateList.valueOf(color(context, R.color.osservio_press)), content, mask);
    }
    static void feedback(View view, int radius) {
        Context context = view.getContext();
        view.setBackground(ripple(context, null, shape(context, android.graphics.Color.WHITE, radius, false)));
    }
    static void typography(TextView text, int size, boolean strong) {
        text.setTextSize(size); text.setIncludeFontPadding(false);
        text.setTypeface(Typeface.create(strong ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    }
    static TextView text(Context context, int size, boolean strong) {
        TextView text = new TextView(context); typography(text, size, strong);
        InspectorAppearance.style(text, () -> text.setTextColor(primary(context))); return text;
    }
}
