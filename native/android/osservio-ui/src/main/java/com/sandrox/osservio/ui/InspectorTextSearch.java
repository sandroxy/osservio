package com.sandrox.osservio.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Literal Unicode-aware content search with a bounded navigation index. */
final class InspectorTextSearch {
    static final int LIMIT = 200;
    final List<int[]> matches = new ArrayList<>();
    final boolean limited;
    InspectorTextSearch(String content, String query) {
        boolean overflow = false;
        if (!query.isEmpty()) {
            Matcher matcher = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(content);
            while (matcher.find()) {
                if (matches.size() == LIMIT) { overflow = true; break; }
                matches.add(new int[] {matcher.start(), matcher.end()});
            }
        }
        limited = overflow;
    }
}
