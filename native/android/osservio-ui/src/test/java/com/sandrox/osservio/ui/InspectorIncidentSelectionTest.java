package com.sandrox.osservio.ui;

import com.sandrox.osservio.core.*;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorIncidentSelectionTest {
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    @Test public void byteLimitIncludesCategoryAndNeverTruncatesSelectedText() {
        String text = String.join("", Collections.nCopies(32767, "é"));
        LogEvent event = LogEvent.restore(ID, 1, 100, LogLevel.ERROR, "ab", text, false, false);
        IncidentSnapshot mark = InspectorIncidentSelection.mark(event, null);
        assertEquals(65536, mark.logs.retainedTextBytes);
        assertSame(event, mark.logs.events.get(0));
        assertTrue(mark.focus.matches(event));
        LogEvent oversized = LogEvent.restore(ID, 2, 101, LogLevel.ERROR, "abc", text, false, false);
        assertThrows(InspectorIncidentSelection.TooLarge.class, () -> InspectorIncidentSelection.mark(oversized, null));
    }
    @Test public void selectedLogPreservesPrivacyAndDefersDetailsCopy() {
        LogEvent event = LogEvent.restore(ID, 1, 100, LogLevel.INFO, "fixture", "[private]", true, false,
            LogDetailsReference.restore(LogDetailsReference.State.AVAILABLE, ID, 3));
        IncidentSnapshot mark = InspectorIncidentSelection.mark(event, null);
        assertTrue(mark.logs.events.get(0).isPrivate);
        assertEquals("[private]", mark.logs.events.get(0).message);
        assertEquals(IncidentPayload.State.NOT_SELECTED, mark.logs.payloads.get(ID).state);
        assertNull(mark.http); assertNull(mark.webSockets);
    }
}
