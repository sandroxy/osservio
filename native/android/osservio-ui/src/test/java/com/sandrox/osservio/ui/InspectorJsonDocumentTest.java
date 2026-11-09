package com.sandrox.osservio.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorJsonDocumentTest {
    @Test public void sharedGrammarFixtures() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "json-reader-cases.tsv"), StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) continue;
            String[] fields = line.split("\t"); String source = new String(Base64.getDecoder().decode(fields[2]), StandardCharsets.UTF_8);
            if (fields[1].equals("ok")) assertEquals(fields[0], source, parse(source).source);
            else expect(fields[1].equals("notJSON") ? InspectorJsonDocument.Failure.NOT_JSON : InspectorJsonDocument.Failure.INVALID, source);
        }
    }
    @Test public void exactTokensDuplicateKeysAndIndependentExpansion() throws Exception {
        String source = "{\"id\":900719925474099312345,\"id\":-0,\"a\":{\"n\":1.2300e+99},\"b\":[{\"名字\":\"🙂\"}]}";
        InspectorJsonDocument document = parse(source);
        assertEquals(4, document.nodes.get(0).children.size());
        assertEquals("900719925474099312345", document.original(1));
        assertEquals("-0", document.original(2)); assertEquals("1.2300e+99", document.original(4));
        assertEquals(document.key(1), document.key(2));
        assertEquals(6, document.rows(Set.of()).size());
        List<InspectorJsonDocument.Row> rows = document.rows(Set.of(3, 5, 6)); Set<Long> identities = new HashSet<>();
        for (InspectorJsonDocument.Row row : rows) assertTrue(identities.add(row.identity()));
        assertEquals(7, document.nodeAt(source.indexOf("名字")));
        assertEquals(Set.of(0, 5, 6), document.ancestors(7));
        for (InspectorJsonDocument.Row row : document.rows(Set.of(5, 6))) assertNotEquals(4, row.node);
        assertTrue(document.token(new InspectorJsonDocument.Row(5, false), Set.of()).contains("{…}"));
    }
    @Test public void budgetsAndCancellation() throws Exception {
        parse("[\"" + "a".repeat(65_532) + "\"]"); expect(InspectorJsonDocument.Failure.LIMITED, "[\"" + "a".repeat(65_533) + "\"]");
        parse("[" + String.join(",", java.util.Collections.nCopies(4_095, "0")) + "]");
        expect(InspectorJsonDocument.Failure.LIMITED, "[" + String.join(",", java.util.Collections.nCopies(4_096, "0")) + "]");
        parse("[".repeat(48) + "0" + "]".repeat(48));
        expect(InspectorJsonDocument.Failure.LIMITED, "[".repeat(49) + "0" + "]".repeat(49));
        AtomicInteger checks = new AtomicInteger();
        try { InspectorJsonDocument.parse("[\"" + "x".repeat(32_000) + "\"]", () -> checks.incrementAndGet() > 3); fail("Expected cancellation"); }
        catch (InspectorJsonDocument.ParseFailure failure) { assertEquals(InspectorJsonDocument.Failure.CANCELLED, failure.reason); }
        assertTrue(checks.get() < 6);
    }
    private static InspectorJsonDocument parse(String source) throws InspectorJsonDocument.ParseFailure { return InspectorJsonDocument.parse(source, () -> false); }
    private static void expect(InspectorJsonDocument.Failure reason, String source) throws Exception {
        try { parse(source); fail("Expected " + reason); }
        catch (InspectorJsonDocument.ParseFailure failure) { assertEquals(reason, failure.reason); }
    }
}
