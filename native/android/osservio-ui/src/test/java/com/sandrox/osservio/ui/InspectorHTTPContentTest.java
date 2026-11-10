package com.sandrox.osservio.ui;

import com.sandrox.osservio.core.HTTPHeader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class InspectorHTTPContentTest {
    @Test public void sharedResponseTypeEvidence() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "http-content-type.tsv"), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t", -1);
            List<HTTPHeader> headers = new ArrayList<>();
            if (!row[1].equals("-")) for (String value : row[1].split("\\|", -1)) headers.add(new HTTPHeader("Content-Type", value));
            assertEquals(row[0], row[2], InspectorHTTPContent.category(headers));
        }
    }
}
