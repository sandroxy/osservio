package com.sandrox.osservio.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Locale;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class InspectorPreferencesTest {
    @Test public void defaultsAndChangesStayScopedToTheInspector() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            String name = "osservio.ui.test." + UUID.randomUUID();
            Context isolated = new ContextWrapper(target) {
                @Override public SharedPreferences getSharedPreferences(String ignored, int mode) { return target.getSharedPreferences(name, mode); }
            };
            Configuration configuration = new Configuration(target.getResources().getConfiguration());
            configuration.setLocale(Locale.ENGLISH); configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
            Context host = new ContextThemeWrapper(isolated, android.R.style.Theme_Material_NoActionBar);
            ((ContextThemeWrapper) host).applyOverrideConfiguration(configuration);
            try {
                Context chinese = InspectorTheme.context(host);
                assertEquals("应用", chinese.getString(R.string.osservio_ui_app));
                assertEquals(Configuration.UI_MODE_NIGHT_NO, chinese.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
                assertEquals("App", host.getString(R.string.osservio_ui_app));
                assertEquals(Configuration.UI_MODE_NIGHT_YES, host.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
                InspectorPreferences.setEnglish(host, true); InspectorPreferences.setDark(host, true);
                Context english = InspectorTheme.context(chinese);
                assertEquals("App", english.getString(R.string.osservio_ui_app));
                assertEquals(Configuration.UI_MODE_NIGHT_YES, english.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
                assertSame(host, ((ContextThemeWrapper) english).getBaseContext());
                assertEquals("应用", chinese.getString(R.string.osservio_ui_app));
                // English-only configurations can still obtain all Chinese entries from the base table.
                TypedArray pairs = host.getResources().obtainTypedArray(R.array.osservio_chinese_translations);
                try {
                    boolean found = false;
                    for (int i = 0; i < pairs.length(); i += 2) if (pairs.getResourceId(i, 0) == R.string.osservio_ui_app) {
                        assertEquals("应用", pairs.getString(i + 1)); found = true;
                    }
                    assertTrue(found);
                } finally { pairs.recycle(); }
            } finally { target.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit(); }
        });
    }
}
