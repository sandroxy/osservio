package com.sandrox.osservio.ui;

import android.content.Context;
import android.content.SharedPreferences;

/** UI preferences are independent of host settings and capture policy. */
final class InspectorPreferences {
    static final String LANGUAGE = "language";
    static final String APPEARANCE = "appearance";
    private InspectorPreferences() { }
    static SharedPreferences storage(Context context) {
        return context.getSharedPreferences("com.sandrox.osservio.inspector", Context.MODE_PRIVATE);
    }
    static boolean english(Context context) { return "en".equals(storage(context).getString(LANGUAGE, "zh-Hans")); }
    static boolean dark(Context context) { return "dark".equals(storage(context).getString(APPEARANCE, "light")); }
    static void setEnglish(Context context, boolean value) { storage(context).edit().putString(LANGUAGE, value ? "en" : "zh-Hans").apply(); }
    static void setDark(Context context, boolean value) { storage(context).edit().putString(APPEARANCE, value ? "dark" : "light").apply(); }
}
