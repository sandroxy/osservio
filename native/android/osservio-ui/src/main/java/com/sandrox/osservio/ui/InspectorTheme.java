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
                if (scoped.dark == dark && scoped.english == english) return host;
                break;
            }
            Context base = ((android.content.ContextWrapper) candidate).getBaseContext();
            if (base == candidate) break; candidate = base;
        }
        if (host instanceof ScopedContext) {
            ScopedContext scoped = (ScopedContext) host;
            if (scoped.dark == dark && scoped.english == english) return host;
            host = scoped.getBaseContext();
        }
        return new ScopedContext(host, dark, english);
    }
    private static final class ScopedContext extends ContextThemeWrapper {
        final boolean dark;
        final boolean english;
        private Resources localized;
        // Both languages are retained in a generated, unqualified resource table.
        @SuppressLint("AppBundleLocaleChanges")
        ScopedContext(Context host, boolean dark, boolean english) {
            super(host, dark ? R.style.OsservioInspectorDark : R.style.OsservioInspectorLight);
            this.dark = dark; this.english = english;
            Configuration configuration = new Configuration();
            configuration.setLocale(english ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE);
            configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            applyOverrideConfiguration(configuration);
        }
        @Override public Resources getResources() {
            if (localized == null) localized = new InspectorResources(super.getResources(), english);
            return localized;
        }
    }
    static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static int color(Context context, int resource) { return context.getResources().getColor(resource, context.getTheme()); }
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
        view.setBackground(interactive ? new RippleDrawable(ColorStateList.valueOf(color(context, R.color.osservio_tint)), fill, null) : fill);
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
