package com.sandrox.osservio.store;

import com.sandrox.osservio.core.EnvironmentSnapshot;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.WebSocketEvent;
import java.util.Locale;

/** Canonical stored names shared by the native incident schema. Java enum spelling is not the schema. */
final class IncidentNames {
    private IncidentNames() { }
    static String name(Enum<?> value) {
        if (value == null) return null;
        if (value == EnvironmentSnapshot.Key.PROCESS_ID) return "processID";
        if (value == HTTPEvent.Source.OKHTTP || value == WebSocketEvent.Source.OKHTTP) return "okHttp";
        String[] words = value.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) result.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        return result.toString();
    }
    static <T extends Enum<T>> T parse(T[] values, String name) {
        for (T value : values) if (name(value).equals(name)) return value;
        throw EvidenceStorageException.invalid();
    }
}
