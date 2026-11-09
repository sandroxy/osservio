package com.sandrox.osservio.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorTextSearchTest {
    @Test public void searchesLiteralTextAndPreservesUnicodeOffsets() {
        InspectorTextSearch literal = new InspectorTextSearch("[error] .* [ERROR]", "[error]");
        assertEquals(2, literal.matches.size());
        assertArrayEquals(new int[] {11, 18}, literal.matches.get(1));
        InspectorTextSearch unicode = new InspectorTextSearch("İ / i / 中文 / 😀", "i");
        assertEquals(2, unicode.matches.size());
        assertArrayEquals(new int[] {0, 1}, unicode.matches.get(0));
        assertArrayEquals(new int[] {4, 5}, unicode.matches.get(1));
        assertArrayEquals(new int[] {13, 15}, new InspectorTextSearch("İ / i / 中文 / 😀", "😀").matches.get(0));
        assertTrue(new InspectorTextSearch("anything", "").matches.isEmpty());
    }
    @Test public void boundsNavigationAndReportsTruncationSeparately() {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < InspectorTextSearch.LIMIT; i++) content.append("x");
        InspectorTextSearch exact = new InspectorTextSearch(content.toString(), "x");
        assertEquals(InspectorTextSearch.LIMIT, exact.matches.size()); assertFalse(exact.limited);
        InspectorTextSearch overflow = new InspectorTextSearch(content.append('x').toString(), "x");
        assertEquals(InspectorTextSearch.LIMIT, overflow.matches.size()); assertTrue(overflow.limited);
        assertTrue(new InspectorTextSearch(content.toString(), "not present").matches.isEmpty());
    }
}
