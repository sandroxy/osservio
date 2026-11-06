package com.sandrox.osservio.store;

import com.sandrox.osservio.core.SystemDiagnostic;
import com.sandrox.osservio.core.SystemDiagnosticBatch;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SystemDiagnosticTest {
    @Test public void sharedClassificationPrecisionAndInvalidEvidence() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "system-diagnostic-cases.tsv"), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t");
            try {
                SystemDiagnostic value = diagnostic(row[1], row[2], row[3]);
                assertEquals(row[0], row[4].toLowerCase(java.util.Locale.ROOT), value.kind.name().replace("_", "").toLowerCase(java.util.Locale.ROOT));
                if (row[2].equals("code")) assertEquals(row[0], row[3], value.metricKit.exceptionCode);
                if (row[1].equals("android") && row[2].equals("start")) assertEquals(row[0], Long.parseLong(row[3]), value.intervalStartMillis);
            } catch (IllegalArgumentException invalid) { assertEquals(row[0], "invalid", row[4]); }
        }
    }
    private long number(String key, String field, String value, long fallback) { return field.equals(key) ? Long.parseLong(value) : fallback; }
    private Long optional(String key, String field, String value) { return field.equals(key) ? Long.valueOf(value) : null; }
    private SystemDiagnostic diagnostic(String source, String field, String value) {
        if (source.equals("android")) return SystemDiagnostic.androidExit(number("start", field, value, 100),
            (int) number("reason", field, value, 4), 0, (int) number("pid", field, value, 123),
            (int) number("importance", field, value, 100), optional("pss", field, value), optional("rss", field, value));
        boolean hang = source.equals("hang");
        SystemDiagnostic.Kind kind = field.equals("kind") ? SystemDiagnostic.Kind.valueOf(value.toUpperCase(java.util.Locale.ROOT)) :
            (hang ? SystemDiagnostic.Kind.HANG : SystemDiagnostic.Kind.CRASH);
        Integer signal = field.equals("signal") ? Integer.valueOf(value) : null;
        Long duration = field.equals("duration") ? Long.valueOf(value) : (hang ? Long.valueOf(150) : null);
        return SystemDiagnostic.metricKit(number("start", field, value, 100), 200, kind, Collections.emptyMap(), signal,
            optional("type", field, value), field.equals("code") ? value : null, duration);
    }
    @Test public void boundedPublicMetadataAndStrictRestoration() {
        String raw = "😀".repeat(65);
        SystemDiagnostic.Text value = SystemDiagnostic.Text.capturePublic(raw);
        assertEquals("😀".repeat(64), value.value); assertTrue(value.isTruncated);
        SystemDiagnostic.Text restored = SystemDiagnostic.Text.restore(value.value, true);
        assertTrue(restored.isTruncated);
        assertThrows(IllegalArgumentException.class, () -> SystemDiagnostic.Text.restore(raw, true));
        assertThrows(IllegalArgumentException.class, () -> SystemDiagnostic.Text.capturePublic(""));
        assertThrows(IllegalArgumentException.class, () -> SystemDiagnostic.Text.capturePublic("\uD800"));
        Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> metadata = new EnumMap<>(SystemDiagnostic.MetadataKey.class);
        metadata.put(SystemDiagnostic.MetadataKey.APP_VERSION, restored);
        SystemDiagnostic item = SystemDiagnostic.metricKit(100, 200, SystemDiagnostic.Kind.CRASH, metadata, null, null, "18446744073709551615", null);
        metadata.clear();
        assertEquals(276, item.textByteCount()); assertEquals(value.value, item.metricKit.metadata.get(SystemDiagnostic.MetadataKey.APP_VERSION).value);
        assertThrows(UnsupportedOperationException.class, () -> item.metricKit.metadata.clear()); assertNull(item.androidExit);
    }
    @Test public void batchPreservesRepeatedEvidenceAndRejectsOverflow() {
        SystemDiagnostic exit = diagnostic("android", "reason", "4"), crash = diagnostic("metric", "kind", "crash");
        SystemDiagnosticBatch batch = new SystemDiagnosticBatch(SystemDiagnostic.Source.ANDROID_EXIT_INFO, 42,
            Arrays.asList(exit, exit), 3, 27, true);
        assertEquals(2, batch.diagnostics.size()); assertEquals(42, batch.observedAtMillis); assertTrue(batch.sourceLimitReached);
        assertThrows(UnsupportedOperationException.class, () -> batch.diagnostics.clear());
        assertThrows(IllegalArgumentException.class, () -> new SystemDiagnosticBatch(SystemDiagnostic.Source.ANDROID_EXIT_INFO, 0, Collections.singletonList(crash), 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new SystemDiagnosticBatch(SystemDiagnostic.Source.ANDROID_EXIT_INFO, 0, Collections.singletonList(exit), 32, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new SystemDiagnosticBatch(SystemDiagnostic.Source.ANDROID_EXIT_INFO, 0, Collections.emptyList(), 0, -1, false));
        assertThrows(IllegalArgumentException.class, () -> new SystemDiagnosticBatch(SystemDiagnostic.Source.ANDROID_EXIT_INFO, 0, Collections.nCopies(33, exit), 0, 0, false));
    }
}
