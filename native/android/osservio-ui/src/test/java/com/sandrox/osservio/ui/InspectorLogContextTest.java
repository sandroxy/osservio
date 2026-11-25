package com.sandrox.osservio.ui;

import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.LogLevel;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorLogContextTest {
    private static final String SESSION = "12345678-1234-1234-1234-123456789abc";
    private static final String OTHER = "12345678-1234-1234-1234-123456789def";
    private static LogEvent event(String session, long sequence, String message) {
        return LogEvent.restore(session, sequence, 100, LogLevel.INFO, "context", message, false, false);
    }
    @Test public void neighborsIgnoreFiltersButNeverCrossCaptureSessions() {
        List<LogEvent> source = new ArrayList<>();
        for (int i = 1; i <= 100; i++) { source.add(event(SESSION, i, "line " + i)); source.add(event(OTHER, i, "other")); }
        LogEvent target = source.get(98);
        InspectorLogContext window = InspectorLogContext.select(source, target);
        assertNotNull(window); assertEquals(41, window.events.size()); assertEquals(20, window.targetIndex);
        assertSame(target, window.events.get(window.targetIndex));
        assertEquals(30, window.events.get(0).sequence); assertEquals(70, window.events.get(40).sequence);
        assertTrue(window.events.stream().allMatch(value -> value.sessionId.equals(SESSION)));
        source.clear(); assertEquals(41, window.events.size());
    }
    @Test public void absentAnchorDoesNotInventContext() {
        assertNull(InspectorLogContext.select(java.util.Collections.singletonList(event(OTHER, 1, "other")), event(SESSION, 1, "target")));
    }
    @Test public void boundsPreserveOriginalTextAndIdentity() {
        List<LogEvent> source = new ArrayList<>(); String large = String.join("", java.util.Collections.nCopies(60000, "x"));
        for (int i = 1; i <= 7; i++) source.add(event(SESSION, i, large));
        InspectorLogContext window = InspectorLogContext.select(source, source.get(3));
        assertNotNull(window); assertTrue(window.events.stream().mapToInt(value -> value.textByteCount).sum() <= InspectorLogContext.TEXT_BYTE_LIMIT);
        assertSame(source.get(3), window.events.get(window.targetIndex));
        for (LogEvent event : window.events) assertEquals(large, event.message);
    }
    @Test public void edgeAnchorAndSessionCaseKeepRecordedOrder() {
        List<LogEvent> source = java.util.Arrays.asList(event(SESSION.toUpperCase(java.util.Locale.ROOT), 1, "first"), event(SESSION, 2, "second"));
        InspectorLogContext window = InspectorLogContext.select(source, event(SESSION, 1, "first"));
        assertNotNull(window); assertEquals(0, window.targetIndex); assertEquals(2, window.events.size());
        assertEquals(2, window.events.get(1).sequence);
    }
}
