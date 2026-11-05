package com.sandrox.osservio.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable source-sanitized environment. Missing facts never become zero or empty text. */
public final class EnvironmentSnapshot {
    public enum Platform { ANDROID, IOS }
    public enum Key {
        OS_VERSION, APP_IDENTIFIER, APP_VERSION, APP_BUILD, MANUFACTURER, DEVICE_MODEL, ARCHITECTURE,
        PHYSICAL_MEMORY_BYTES, PROCESS_RESIDENT_BYTES, PROCESS_PROPORTIONAL_BYTES, PROCESS_FOOTPRINT_BYTES,
        RUNTIME_HEAP_BYTES, RUNTIME_HEAP_LIMIT_BYTES, PROCESS_ID, BATTERY_PERCENT, LOW_POWER_MODE, THERMAL_STATE;
        private boolean numeric() {
            switch (this) {
                case PHYSICAL_MEMORY_BYTES: case PROCESS_RESIDENT_BYTES: case PROCESS_PROPORTIONAL_BYTES: case PROCESS_FOOTPRINT_BYTES:
                case RUNTIME_HEAP_BYTES: case RUNTIME_HEAP_LIMIT_BYTES: case PROCESS_ID: case BATTERY_PERCENT: return true;
                default: return false;
            }
        }
    }
    /** Only public application/system metadata belongs here, never user identifiers or arbitrary context. */
    public static final class Fact {
        public enum Type { TEXT, NUMBER, FLAG, UNAVAILABLE, UNSUPPORTED }
        public final Type type;
        public final String text;
        public final Long number;
        public final Boolean flag;
        private Fact(Type type, String text, Long number, Boolean flag) {
            this.type = type; this.text = text; this.number = number; this.flag = flag;
        }
        public static Fact text(String text) { return new Fact(Type.TEXT, Objects.requireNonNull(text, "text"), null, null); }
        public static Fact number(long number) { return new Fact(Type.NUMBER, null, number, null); }
        public static Fact flag(boolean flag) { return new Fact(Type.FLAG, null, null, flag); }
        public static final Fact UNAVAILABLE = new Fact(Type.UNAVAILABLE, null, null, null);
        public static final Fact UNSUPPORTED = new Fact(Type.UNSUPPORTED, null, null, null);
    }
    public static final class Value {
        public final Fact fact;
        public final boolean isTruncated;
        private Value(Fact fact, boolean isTruncated) { this.fact = fact; this.isTruncated = isTruncated; }
    }
    public final Platform platform;
    public final long timestampMillis;
    public final Map<Key, Value> values;
    private EnvironmentSnapshot(Platform platform, long timestampMillis, Map<Key, Value> values) {
        this.platform = platform; this.timestampMillis = timestampMillis; this.values = Collections.unmodifiableMap(values);
    }
    public static EnvironmentSnapshot capture(Platform platform, Map<Key, Fact> publicFacts, long timestampMillis) {
        Objects.requireNonNull(platform, "platform"); Objects.requireNonNull(publicFacts, "publicFacts");
        if (publicFacts.size() != Key.values().length) throw new IllegalArgumentException("Every environment key needs an explicit state");
        Map<Key, Value> values = new EnumMap<>(Key.class);
        for (Key key : Key.values()) {
            Fact fact = publicFacts.get(key);
            if (fact == null) throw new IllegalArgumentException("Missing environment field");
            boolean truncated = false;
            switch (fact.type) {
                case TEXT:
                    if (key.numeric() || key == Key.LOW_POWER_MODE || fact.text.isEmpty()) throw invalid();
                    BoundedText bounded = BoundedText.prefix(fact.text, 256);
                    if (bounded == null) throw invalid();
                    fact = Fact.text(bounded.text); truncated = bounded.truncated;
                    break;
                case NUMBER:
                    if (!key.numeric() || fact.number < 0 || (key == Key.BATTERY_PERCENT && fact.number > 100)) throw invalid();
                    break;
                case FLAG: if (key != Key.LOW_POWER_MODE) throw invalid(); break;
                case UNAVAILABLE: case UNSUPPORTED: break;
                default: throw invalid();
            }
            values.put(key, new Value(fact, truncated));
        }
        return new EnvironmentSnapshot(platform, timestampMillis, values);
    }
    /** Restores previously sanitized facts and flags. It does not silently truncate oversized stored values. */
    public static EnvironmentSnapshot restore(Platform platform, Map<Key, Fact> publicFacts, Set<Key> truncatedKeys, long timestampMillis) {
        EnvironmentSnapshot checked = capture(platform, publicFacts, timestampMillis);
        Objects.requireNonNull(truncatedKeys, "truncatedKeys");
        Map<Key, Value> values = new EnumMap<>(checked.values);
        for (Value value : values.values()) if (value.isTruncated) throw invalid();
        for (Key key : truncatedKeys) {
            Value value = values.get(key);
            if (value == null || value.fact.type != Fact.Type.TEXT) throw invalid();
            values.put(key, new Value(value.fact, true));
        }
        return new EnvironmentSnapshot(platform, timestampMillis, values);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid environment value type or range"); }
}
