package com.sandrox.osservio.ui;

import com.sandrox.osservio.core.HTTPHeader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorFormDocumentTest {
    @Test public void sharedFieldsAndEncodingFixtures() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "form-reader-cases.tsv"), StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) continue;
            String[] row = line.split("\t", -1); String source = decode(row[2]);
            if (row[1].equals("invalid")) { expect(source, InspectorFormDocument.Failure.INVALID); continue; }
            InspectorFormDocument document = InspectorFormDocument.parse(source, () -> false);
            String[] fields = row[3].equals("-") ? new String[0] : row[3].split(",");
            assertEquals(row[0], fields.length, document.fields.size());
            for (int i = 0; i < fields.length; i++) {
                String[] expected = fields[i].split(":", -1); InspectorFormDocument.Field field = document.fields.get(i);
                assertEquals(row[0], decode(expected[0]), field.name); assertEquals(row[0], decode(expected[1]), field.value);
                assertEquals(row[0], expected[2].equals("1"), field.hasEquals); assertEquals(row[0], decode(expected[3]), field.original);
            }
        }
    }
    private static String decode(String value) { return new String(java.util.Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
    @Test public void limitsCancellationAndDecodedSearchMapping() throws Exception {
        StringBuilder fields = new StringBuilder(); for (int i = 0; i < 256; i++) fields.append("k=v&");
        assertEquals(256, InspectorFormDocument.parse(fields.toString(), () -> false).fields.size());
        expect(fields + "k=v", InspectorFormDocument.Failure.LIMITED);
        expect(new String(new char[65_537]).replace('\0', 'x'), InspectorFormDocument.Failure.LIMITED);
        expect("a=\uD800", InspectorFormDocument.Failure.INVALID);
        try { InspectorFormDocument.parse("a=b", () -> true); fail(); } catch (InspectorFormDocument.ParseFailure error) { assertEquals(InspectorFormDocument.Failure.CANCELLED, error.reason); }
        InspectorFormDocument document = InspectorFormDocument.parse("a=%E4%BD%A0%E5%A5%BD&b=%F0%9F%99%82", () -> false);
        InspectorTextSearch matches = new InspectorTextSearch(document.searchText, "🙂");
        assertEquals(1, matches.matches.size()); assertEquals(1, document.fieldAt(matches.matches.get(0)[0])); assertEquals(-1, document.fieldAt(-1));
    }
    @Test public void requiresObservedContentTypeAndSupportedCharset() {
        assertTrue(InspectorHTTPContent.isForm(Collections.singletonList(new HTTPHeader("Content-Type", "application/x-www-form-urlencoded; charset=\"UTF-8\""))));
        assertFalse(InspectorHTTPContent.isForm(Collections.emptyList()));
        assertFalse(InspectorHTTPContent.isForm(Arrays.asList(new HTTPHeader("content-type", "application/x-www-form-urlencoded"), new HTTPHeader("content-type", "application/json"))));
        assertFalse(InspectorHTTPContent.isForm(Collections.singletonList(new HTTPHeader("Content-Type", "application/x-www-form-urlencoded; charset=gbk"))));
        InspectorReadContent content = new InspectorReadContent("a=1", "", true); content.isForm = true;
        assertTrue(content.withNoticePrefix(() -> "Saved").isForm);
    }
    private static void expect(String source, InspectorFormDocument.Failure expected) throws Exception {
        try { InspectorFormDocument.parse(source, () -> false); fail(source); }
        catch (InspectorFormDocument.ParseFailure invalid) { assertEquals(expected, invalid.reason); }
    }
}
