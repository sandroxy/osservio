package com.sandrox.osservio.export;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class IncidentDocumentsTest {
    @Test public void documentsMatchIndependentSharedFixtures() throws Exception {
        for (String name : new String[] {"absent", "empty", "complete", "trusted-content", "structured-log", "diagnostic-android", "diagnostic-crash", "diagnostic-hang", "diagnostic-signal", "diagnostic-tombstone", "diagnostic-tombstone-unavailable"}) {
            JSONObject fixture;
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("incident-export/" + name + ".json");
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int size;
                while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
                fixture = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
            IncidentDocuments documents = IncidentDocuments.encode(IncidentDocumentFixture.make(name));
            same(fixture.get("metadata"), new JSONObject(documents.metadataJSON), name + ".metadata");
            same(fixture.get("payloads"), new JSONObject(documents.payloadsJSON), name + ".payloads");
            assertFalse(documents.metadataJSON.contains("synthetic-secret"));
        }
    }
    @Test public void oversizedCombinedEvidenceIsRejectedBeforeEncoding() {
        List<LogEvent> logs = new ArrayList<>(); String message = repeated('x', 65529);
        for (int index = 1; index <= 16; index++) logs.add(LogEvent.restore(IncidentDocumentFixture.SESSION, index, 0, LogLevel.INFO, "fixture", message, false, false));
        List<BreadcrumbEvent> crumbs = new ArrayList<>();
        for (int index = 1; index <= 256; index++) crumbs.add(BreadcrumbEvent.restore(UUID.randomUUID().toString(), IncidentDocumentFixture.SESSION,
            index, 0, null, BreadcrumbEvent.Kind.HOST_MARKER, "a", repeated('x', 4095), false, false, null, BreadcrumbEvent.Retention.MEMORY_ONLY));
        IncidentSnapshot snapshot = IncidentSnapshot.restore(IncidentDocumentFixture.ID, 0, IncidentSnapshot.Note.capture(LogMessage.publicText("x")), null,
            IncidentSnapshot.Breadcrumbs.restore(crumbs, 256, 0), IncidentLogs.restore(logs, 0, 16, 0), null, null);
        assertTrue(snapshot.textByteCount > IncidentDocuments.MAXIMUM_TEXT_BYTES);
        assertThrows(IllegalArgumentException.class, () -> IncidentDocuments.encode(snapshot));
    }
    private static String repeated(char value, int count) { char[] text = new char[count]; java.util.Arrays.fill(text, value); return new String(text); }
    private static void same(Object expected, Object actual, String path) throws Exception {
        if (expected instanceof JSONObject) {
            assertTrue(path, actual instanceof JSONObject); JSONObject left = (JSONObject) expected, right = (JSONObject) actual;
            assertEquals(path, left.length(), right.length());
            for (Iterator<String> keys = left.keys(); keys.hasNext();) { String key = keys.next(); assertTrue(path + "." + key, right.has(key)); same(left.get(key), right.get(key), path + "." + key); }
        } else if (expected instanceof JSONArray) {
            assertTrue(path, actual instanceof JSONArray); JSONArray left = (JSONArray) expected, right = (JSONArray) actual;
            assertEquals(path, left.length(), right.length()); for (int index = 0; index < left.length(); index++) same(left.get(index), right.get(index), path + "[" + index + "]");
        } else { assertEquals(path, expected, actual); }
    }
}
