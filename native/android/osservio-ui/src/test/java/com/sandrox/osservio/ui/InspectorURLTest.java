package com.sandrox.osservio.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorURLTest {
    @Test public void readableURLsKeepDelimitersAndPrivacy() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "readable-urls.tsv"), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t", -1); InspectorURL value = new InspectorURL(row[1]);
            assertTrue(row[0], value.available); assertEquals(row[0], row[2], value.origin);
            assertEquals(row[0], row[3], value.path); assertEquals(row[0], Boolean.parseBoolean(row[4]), value.queryHidden);
            assertEquals(row[0], Boolean.parseBoolean(row[5]), value.pathHidden);
        }
    }
    @Test public void malformedInputHasNoInventedOrigin() {
        assertFalse(new InspectorURL("[unavailable]").available);
        assertEquals("", new InspectorURL("[unavailable]").origin);
        assertEquals("%Q0%", InspectorURL.readable("%Q0%"));
    }
}
