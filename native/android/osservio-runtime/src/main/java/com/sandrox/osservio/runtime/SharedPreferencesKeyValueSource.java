package com.sandrox.osservio.runtime;

import android.content.SharedPreferences;
import com.sandrox.osservio.core.KeyValueInspection;
import com.sandrox.osservio.core.KeyValueSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.json.JSONObject;

/** Reads one host-owned, small preference file through its public API. No edit or listener is installed.
 * Android's getAll copies the complete map before filtering; use a custom KeyValueSource for large stores.
 * The immutable prefix restricts enumeration AND direct reads. Empty prefix explicitly exposes this file.
 */
public final class SharedPreferencesKeyValueSource implements KeyValueSource {
    private final SharedPreferences preferences;
    private final String prefix;
    public SharedPreferencesKeyValueSource(SharedPreferences preferences, String prefix) {
        this.preferences = Objects.requireNonNull(preferences); this.prefix = Objects.requireNonNull(prefix);
        if (prefix.length() > KeyValueInspection.MAX_KEY_BYTES || prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > KeyValueInspection.MAX_KEY_BYTES)
            throw new IllegalArgumentException("Storage prefix too long");
    }
    @Override public boolean visitKeys(KeyVisitor visitor) {
        int visited = 0;
        for (String key : preferences.getAll().keySet()) {
            if (++visited > KeyValueInspection.MAX_SCANNED_KEYS || key.startsWith(prefix) && !visitor.visit(key)) return false;
        }
        return true;
    }
    @Override public KeyValueInspection.Value read(String key) {
        if (!key.startsWith(prefix)) return KeyValueInspection.Value.unavailable(KeyValueInspection.State.MISSING);
        // One coherent platform snapshot; never invoke business cache getters with expiry/access side effects.
        Map<String, ?> values = preferences.getAll(); Object value = values.get(key);
        if (value == null) return KeyValueInspection.Value.unavailable(KeyValueInspection.State.MISSING);
        if (value instanceof String) return KeyValueInspection.Value.text(KeyValueInspection.Kind.STRING, (String) value);
        if (value instanceof Boolean) return KeyValueInspection.Value.text(KeyValueInspection.Kind.BOOLEAN, value.toString());
        if (value instanceof Integer || value instanceof Long) return KeyValueInspection.Value.text(KeyValueInspection.Kind.INTEGER, value.toString());
        if (value instanceof Float) return KeyValueInspection.Value.text(KeyValueInspection.Kind.NUMBER, value.toString());
        if (value instanceof Set<?>) return strings((Set<?>) value);
        return KeyValueInspection.Value.unavailable(KeyValueInspection.State.UNSUPPORTED);
    }
    private static KeyValueInspection.Value strings(Set<?> values) {
        if (values.size() > 1024) return KeyValueInspection.Value.unavailable(KeyValueInspection.State.TOO_LARGE);
        ArrayList<String> strings = new ArrayList<>(); int budget = KeyValueInspection.MAX_VALUE_BYTES - 2;
        for (Object value : values) {
            if (!(value instanceof String)) return KeyValueInspection.Value.unavailable(KeyValueInspection.State.UNSUPPORTED);
            String string = (String) value;
            // JSON escaping consumes at most six bytes per UTF-16 unit; check before allocating it.
            if (string.length() > (budget - 3) / 6) return KeyValueInspection.Value.unavailable(KeyValueInspection.State.TOO_LARGE);
            String quoted = JSONObject.quote(string); budget -= quoted.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
            strings.add(quoted);
        }
        Collections.sort(strings);
        return KeyValueInspection.Value.text(KeyValueInspection.Kind.STRING_SET, "[" + android.text.TextUtils.join(",", strings) + "]");
    }
}
