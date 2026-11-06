package com.sandrox.osservio.store;

import com.sandrox.osservio.core.AndroidTombstone;
import com.sandrox.osservio.core.SystemDiagnostic;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AndroidTombstoneTest {
    private static final String MAX = "18446744073709551615";
    @Test public void sharedBoundsAndEvidenceInvariants() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "android-tombstone-cases.tsv"), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t"); assertEquals(4, row.length);
            try { make(row[1], row[2]); assertEquals(row[0], "valid", row[3]); }
            catch (IllegalArgumentException invalid) { assertEquals(row[0], "invalid", row[3]); }
        }
    }
    @Test public void exactAddressesImmutableFramesAndTextBudget() {
        AndroidTombstone value = make("frames", "128");
        assertEquals(MAX, value.faultAddress); assertEquals(MAX, value.frames.get(127).programCounter);
        assertEquals(MAX, value.frames.get(127).relativeProgramCounter);
        assertTrue(value.frameLimitReached()); assertEquals(5120, value.textByteCount());
        assertFalse(make("frames", "0").frameLimitReached());
        assertEquals(0, make("frames", "0").textByteCount());
        List<AndroidTombstone.Frame> frames = new ArrayList<>(value.frames);
        AndroidTombstone copy = AndroidTombstone.restore(1, 6, -6, 1234, null, frames);
        frames.clear(); assertEquals(128, copy.frames.size());
        assertThrows(UnsupportedOperationException.class, () -> copy.frames.clear());
        for (String invalid : new String[] {"", "00", "+1", "-0", " 1", "1\n"}) {
            assertThrows(IllegalArgumentException.class, () -> make("pc", invalid));
            assertThrows(IllegalArgumentException.class, () -> make("relative_pc", invalid));
            assertThrows(IllegalArgumentException.class, () -> make("address", invalid));
        }
    }
    @Test public void sharedSourceAndReadStateInvariants() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "android-tombstone-states.tsv"), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t");
            try {
                SystemDiagnostic value = SystemDiagnostic.androidExit(1000, Integer.parseInt(row[1]), 6, 123, 100, null, null,
                    IncidentNames.parse(SystemDiagnostic.TombstoneState.values(), row[2]), Boolean.parseBoolean(row[3]) ? make("frames", "2") : null);
                assertEquals(row[0], "valid", row[4]);
                assertEquals(Boolean.parseBoolean(row[3]) ? 80 : 0, value.textByteCount());
            } catch (IllegalArgumentException invalid) { assertEquals(row[0], "invalid", row[4]); }
        }
        assertEquals(SystemDiagnostic.TombstoneState.NOT_REQUESTED, SystemDiagnostic.androidExit(1000, 5, 6, 123, 100, null, null).androidExit.tombstoneState);
        assertEquals(SystemDiagnostic.TombstoneState.NOT_APPLICABLE, SystemDiagnostic.androidExit(1000, 6, 0, 123, 100, null, null).androidExit.tombstoneState);
    }
    private AndroidTombstone make(String field, String value) {
        String build = "0123456789abcdef0123456789abcdef01234567";
        if (field.equals("build_id")) {
            build = value.equals("-") ? null : value.equals("empty") ? "" :
                value.equals("128") || value.equals("130") ? "a".repeat(Integer.parseInt(value)) : value;
        }
        AndroidTombstone.Frame frame = AndroidTombstone.Frame.restore(field.equals("pc") ? value : MAX,
            field.equals("relative_pc") ? value : MAX, build);
        return AndroidTombstone.restore(Integer.parseInt(field.equals("architecture") ? value : "1"),
            Integer.parseInt(field.equals("signal") ? value : "6"), Integer.parseInt(field.equals("code") ? value : "-6"),
            Integer.parseInt(field.equals("thread") ? value : "1234"), field.equals("address") ? (value.equals("-") ? null : value) : MAX,
            Collections.nCopies(field.equals("frames") ? Integer.parseInt(value) : 2, frame));
    }
}
