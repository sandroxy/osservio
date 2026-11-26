package com.sandrox.osservio.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.KeyValueInspection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SharedPreferencesKeyValueSourceTest {
    @Test public void exactScopeAndTypesStayReadOnly() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("storage-test-" + UUID.randomUUID(), Context.MODE_PRIVATE);
        try {
            assertTrue(preferences.edit().putString("a:text", "{\"name\":\"示例🙂\"}").putBoolean("a:flag", true).putString("a:empty", "")
                .putLong("a:count", Long.MAX_VALUE).putStringSet("a:set", new HashSet<>(Arrays.asList("two", "one")))
                .putString("b:secret", "other account").commit());
            Map<String, ?> before = preferences.getAll();
            SharedPreferencesKeyValueSource source = new SharedPreferencesKeyValueSource(preferences, "a:");
            ArrayList<String> keys = new ArrayList<>(); assertTrue(source.visitKeys(key -> { keys.add(key); return true; }));
            assertEquals(5, keys.size()); assertFalse(keys.contains("b:secret"));
            assertEquals(KeyValueInspection.State.MISSING, source.read("b:secret").state);
            assertEquals(KeyValueInspection.State.AVAILABLE, source.read("a:empty").state);
            assertEquals("9223372036854775807", source.read("a:count").text);
            assertEquals(KeyValueInspection.Kind.BOOLEAN, source.read("a:flag").kind);
            assertEquals("[\"one\",\"two\"]", source.read("a:set").text);
            assertEquals(before, preferences.getAll());
            preferences.edit().remove("a:text").commit();
            assertEquals(KeyValueInspection.State.MISSING, source.read("a:text").state);
        } finally { assertTrue(preferences.edit().clear().commit()); }
    }
    @Test public void oversizedValuesRemainBoundedAndVisibleAsPartial() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("storage-test-" + UUID.randomUUID(), Context.MODE_PRIVATE);
        try {
            String text = new String(new char[65535]).replace('\0', 'a') + "🙂";
            preferences.edit().putString("text", text).commit();
            KeyValueInspection.Value value = new SharedPreferencesKeyValueSource(preferences, "").read("text");
            assertTrue(value.truncated); assertEquals(65535, value.text.length()); assertEquals(text, preferences.getString("text", null));
        } finally { assertTrue(preferences.edit().clear().commit()); }
    }
}
