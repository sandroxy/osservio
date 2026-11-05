package com.sandrox.osservio.store;

import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.BreadcrumbSnapshot;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.LogClock;
import com.sandrox.osservio.core.LogMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public final class IncidentSnapshotTest {
    private String decode(String input) { return input.equals("~") ? "" : new String(Base64.getDecoder().decode(input), StandardCharsets.UTF_8); }
    @Test public void sharedNotePrivacyAndUnicodeBoundaries() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "incident-note-cases.tsv"), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t");
            String raw = decode(row[2]).repeat(Integer.parseInt(row[3]));
            String expected = decode(row[4]).repeat(Integer.parseInt(row[5])) + decode(row[6]);
            IncidentSnapshot snapshot = IncidentSnapshot.capture(row[1].equals("private") ? LogMessage.privateText(raw) : LogMessage.publicText(raw), null, null);
            assertEquals(row[0], expected, snapshot.note.text); assertEquals(row[1].equals("private"), snapshot.note.isPrivate);
            assertEquals(Boolean.parseBoolean(row[7]), snapshot.note.isTruncated); assertTrue(snapshot.note.text.getBytes(StandardCharsets.UTF_8).length <= 4096);
        }
    }
    private BreadcrumbEvent event(long sequence, String text) {
        return BreadcrumbEvent.restore(UUID.randomUUID().toString(), "12345678-1234-1234-1234-123456789abc", sequence, 100, 1L,
            BreadcrumbEvent.Kind.HOST_MARKER, "a", text, false, false, null, BreadcrumbEvent.Retention.MEMORY_ONLY);
    }
    @Test public void snapshotKeepsNewestBoundedContextAndOriginalTimes() {
        List<BreadcrumbEvent> events = new ArrayList<>(); for (int i = 1; i <= 5; i++) events.add(event(i, "x".repeat(31)));
        BreadcrumbSnapshot source = new BreadcrumbSnapshot(events, 160, 7, 1);
        EnumMap<EnvironmentSnapshot.Key, EnvironmentSnapshot.Fact> facts = new EnumMap<>(EnvironmentSnapshot.Key.class);
        for (EnvironmentSnapshot.Key key : EnvironmentSnapshot.Key.values()) facts.put(key, EnvironmentSnapshot.Fact.UNAVAILABLE);
        EnvironmentSnapshot environment = EnvironmentSnapshot.capture(EnvironmentSnapshot.Platform.ANDROID, facts, 42);
        IncidentSnapshot captured = IncidentSnapshot.capture(null, environment, source, 3, 64, LogClock.SYSTEM);
        assertEquals(events.subList(3, 5), captured.breadcrumbs.events); assertEquals(3, captured.breadcrumbs.omittedEventCount);
        assertEquals(7, captured.breadcrumbs.sourceEvictedCount); assertEquals(64, captured.breadcrumbs.retainedTextBytes);
        assertEquals(42, captured.environment.timestampMillis); assertNull(captured.note); assertEquals(5, source.events.size());
        IncidentSnapshot restored = IncidentSnapshot.restore(captured.id, captured.timestampMillis, captured.note, captured.environment, captured.breadcrumbs);
        assertEquals(captured.id, restored.id); assertEquals(captured.timestampMillis, restored.timestampMillis);
        assertNull(IncidentSnapshot.capture(null, null, null).breadcrumbs);
        assertEquals(0, IncidentSnapshot.capture(null, null, new BreadcrumbSnapshot(Collections.emptyList(), 0, 0, 0)).breadcrumbs.sourceEventCount);
        assertThrows(UnsupportedOperationException.class, () -> captured.breadcrumbs.events.clear());
    }
    @Test public void invalidRestorationAndConfigurationAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> IncidentSnapshot.capture(null, null, null, 0, 64, LogClock.SYSTEM));
        assertThrows(IllegalArgumentException.class, () -> IncidentSnapshot.Note.restore("raw-secret", true, false));
        assertThrows(IllegalArgumentException.class, () -> IncidentSnapshot.Note.restore("[private]", true, true));
        assertThrows(IllegalArgumentException.class, () -> IncidentSnapshot.Note.restore("x".repeat(4097), false, true));
        assertThrows(IllegalArgumentException.class, () -> IncidentSnapshot.Note.restore("\uD800", false, false));
        assertThrows(IllegalArgumentException.class, () -> IncidentSnapshot.restore("1-1-1-1-1", 0, null, null, null));
        BreadcrumbEvent value = event(1, "value");
        assertThrows(IllegalArgumentException.class, () -> IncidentSnapshot.Breadcrumbs.restore(Arrays.asList(value, value), 2, 0));
        assertThrows(IllegalArgumentException.class, () -> IncidentSnapshot.Breadcrumbs.restore(Collections.singletonList(value), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> IncidentSnapshot.Breadcrumbs.restore(Collections.emptyList(), 0, -1));
    }
    @Test public void environmentRestorationPreservesFlagsAndRejectsRepair() {
        EnumMap<EnvironmentSnapshot.Key, EnvironmentSnapshot.Fact> facts = new EnumMap<>(EnvironmentSnapshot.Key.class);
        for (EnvironmentSnapshot.Key key : EnvironmentSnapshot.Key.values()) facts.put(key, EnvironmentSnapshot.Fact.UNAVAILABLE);
        facts.put(EnvironmentSnapshot.Key.APP_VERSION, EnvironmentSnapshot.Fact.text("version-prefix"));
        facts.put(EnvironmentSnapshot.Key.PHYSICAL_MEMORY_BYTES, EnvironmentSnapshot.Fact.number(Long.MAX_VALUE));
        EnvironmentSnapshot restored = EnvironmentSnapshot.restore(EnvironmentSnapshot.Platform.ANDROID, facts, Collections.singleton(EnvironmentSnapshot.Key.APP_VERSION), Long.MIN_VALUE);
        assertTrue(restored.values.get(EnvironmentSnapshot.Key.APP_VERSION).isTruncated); assertEquals(Long.MIN_VALUE, restored.timestampMillis);
        assertEquals(Long.valueOf(Long.MAX_VALUE), restored.values.get(EnvironmentSnapshot.Key.PHYSICAL_MEMORY_BYTES).fact.number);
        assertThrows(IllegalArgumentException.class, () -> EnvironmentSnapshot.restore(EnvironmentSnapshot.Platform.ANDROID, facts, Collections.singleton(EnvironmentSnapshot.Key.PHYSICAL_MEMORY_BYTES), 0));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentSnapshot.restore(EnvironmentSnapshot.Platform.ANDROID, facts, Collections.singleton(EnvironmentSnapshot.Key.OS_VERSION), 0));
        facts.put(EnvironmentSnapshot.Key.APP_VERSION, EnvironmentSnapshot.Fact.text("x".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentSnapshot.restore(EnvironmentSnapshot.Platform.ANDROID, facts, Collections.singleton(EnvironmentSnapshot.Key.APP_VERSION), 0));
    }
}
