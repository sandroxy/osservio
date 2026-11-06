package com.sandrox.osservio.store;

import com.sandrox.osservio.core.AppleSignalCrash;
import com.sandrox.osservio.core.SystemDiagnostic;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AppleSignalCrashTest {
    private static final String MAX = "18446744073709551615";
    @Test public void sharedRestorationBoundsAndFieldInvariants() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "apple-signal-cases.tsv"), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t"); assertEquals(4, row.length);
            try { make(row[1], row[2]); assertEquals(row[0], "valid", row[3]); }
            catch (IllegalArgumentException invalid) { assertEquals(row[0], "invalid", row[3]); }
        }
    }
    @Test public void exactAddressesMissingEvidenceAndCompletenessFlags() {
        AppleSignalCrash value = make("frames", "128");
        assertEquals(MAX, value.faultAddress); assertEquals(MAX, value.frames.get(127));
        assertEquals(1_700_000_000_000L, value.timestampMillis); assertEquals(81, value.textByteCount());
        assertTrue(value.frameLimitReached()); assertFalse(value.imageLimitReached()); assertTrue(value.imageCacheIncomplete);
        assertEquals(MAX, value.images.get(0).cpuSubtype);
        AppleSignalCrash unavailable = make("missing_stack", "-");
        assertNull(unavailable.threadNumber); assertTrue(unavailable.frames.isEmpty()); assertFalse(unavailable.frameLimitReached());
        assertTrue(make("images", "512").imageLimitReached());
        for (String invalid : new String[] {"-1", "+1", "01", "", "18446744073709551616"}) {
            assertThrows(IllegalArgumentException.class, () -> make("address", invalid));
        }
    }
    @Test public void evidenceOwnsImmutableBoundedCopies() {
        AppleSignalCrash original = make("code", "#0");
        List<String> frames = new ArrayList<>(original.frames);
        List<AppleSignalCrash.Image> images = new ArrayList<>(original.images);
        Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> metadata = new EnumMap<>(original.metadata);
        AppleSignalCrash copy = AppleSignalCrash.restore(original.reportID, original.timestampMillis, original.signal, original.code,
            original.faultAddress, original.threadNumber, frames, images, metadata, true);
        frames.clear(); images.clear(); metadata.clear();
        assertEquals(2, copy.frames.size()); assertEquals(1, copy.images.size()); assertEquals(1, copy.metadata.size());
        assertThrows(UnsupportedOperationException.class, () -> copy.frames.clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.images.clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.metadata.clear());
    }
    private AppleSignalCrash make(String field, String value) {
        String[] pair = field.equals("signal_code") ? value.split(":") : new String[] {"SIGABRT", field.equals("code") ? value : "#0"};
        AppleSignalCrash.Signal signal = null;
        for (AppleSignalCrash.Signal candidate : AppleSignalCrash.Signal.values()) if (candidate.wireName.equals(pair[0])) signal = candidate;
        if (signal == null) throw new IllegalArgumentException("Unsupported signal");
        boolean noCPU = field.equals("missing_cpu");
        AppleSignalCrash.Image image = AppleSignalCrash.Image.restore("4096", field.equals("image_size") ? value : "4096",
            field.equals("image_uuid") ? (value.equals("-") ? null : value) : "12345678123412341234abcdef123456",
            noCPU || (field.equals("cpu_type") && value.equals("-")) ? null : "16777228", noCPU ? null : MAX);
        String text = field.equals("metadata") ? (value.equals("nul") ? "before\0after" : "x".repeat(257)) : "😀";
        Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> metadata = new EnumMap<>(SystemDiagnostic.MetadataKey.class);
        metadata.put(SystemDiagnostic.MetadataKey.APP_VERSION, SystemDiagnostic.Text.capturePublic(text));
        Long thread = field.equals("missing_stack") || (field.equals("thread") && value.equals("-")) ? null :
            Long.valueOf(field.equals("thread") ? value : "4294967295");
        return AppleSignalCrash.restore(field.equals("id") ? value : "12345678-1234-1234-1234-abcdef123456",
            Long.parseLong(field.equals("time") ? value : "1700000000000"), signal, pair[1], field.equals("address") ? value : MAX, thread,
            Collections.nCopies(field.equals("frames") ? Integer.parseInt(value) : (field.equals("missing_stack") ? 0 : 2), MAX),
            Collections.nCopies(field.equals("images") ? Integer.parseInt(value) : 1, image), metadata, true);
    }
}
