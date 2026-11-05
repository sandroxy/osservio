package com.sandrox.osservio.store;

import com.sandrox.osservio.core.EnvironmentSnapshot;
import com.sandrox.osservio.core.EnvironmentSnapshot.Fact;
import com.sandrox.osservio.core.EnvironmentSnapshot.Key;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class EnvironmentTest {
    private static Map<Key, Fact> emptyFacts() {
        Map<Key, Fact> result = new EnumMap<>(Key.class);
        for (Key key : Key.values()) result.put(key, Fact.UNSUPPORTED);
        return result;
    }
    private static String decode(String input) { return input.equals("~") ? "" : new String(Base64.getDecoder().decode(input), StandardCharsets.UTF_8); }
    @Test public void sharedValueTypesRangesPrecisionAndUnicodeFixtures() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "environment-values.tsv"), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t");
            Key key = Key.valueOf(row[1].replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT));
            String raw = decode(row[3]);
            Map<Key, Fact> facts = emptyFacts();
            switch (row[2]) {
                case "text": facts.put(key, Fact.text(raw)); break;
                case "number": facts.put(key, Fact.number(Long.parseLong(raw))); break;
                case "flag": facts.put(key, Fact.flag(Boolean.parseBoolean(raw))); break;
                case "unavailable": facts.put(key, Fact.UNAVAILABLE); break;
                case "unsupported": facts.put(key, Fact.UNSUPPORTED); break;
                default: throw new AssertionError("Unknown fixture type");
            }
            if (row[4].equals("invalid")) {
                assertThrows(row[0], IllegalArgumentException.class, () -> EnvironmentSnapshot.capture(EnvironmentSnapshot.Platform.ANDROID, facts, 123));
                continue;
            }
            EnvironmentSnapshot snapshot = EnvironmentSnapshot.capture(EnvironmentSnapshot.Platform.ANDROID, facts, 123);
            EnvironmentSnapshot.Value value = snapshot.values.get(key);
            String actual;
            switch (value.fact.type) {
                case TEXT: actual = value.fact.text; break;
                case NUMBER: actual = value.fact.number.toString(); break;
                case FLAG: actual = value.fact.flag.toString(); break;
                default: actual = value.fact.type.name().toLowerCase(Locale.ROOT);
            }
            assertEquals(row[0], decode(row[5]), actual);
            assertEquals(row[0], Boolean.parseBoolean(row[6]), value.isTruncated);
            assertEquals(123, snapshot.timestampMillis);
        }
    }
    @Test public void missingKeysFailAndCapturedFactsAreImmutable() {
        Map<Key, Fact> facts = emptyFacts(); facts.put(Key.APP_VERSION, Fact.text("1.0"));
        EnvironmentSnapshot snapshot = EnvironmentSnapshot.capture(EnvironmentSnapshot.Platform.ANDROID, facts, 123);
        facts.put(Key.APP_VERSION, Fact.text("2.0"));
        assertEquals("1.0", snapshot.values.get(Key.APP_VERSION).fact.text);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.values.clear());
        facts.remove(Key.OS_VERSION);
        assertThrows(IllegalArgumentException.class, () -> EnvironmentSnapshot.capture(EnvironmentSnapshot.Platform.ANDROID, facts, 123));
    }
}
