package com.sandrox.osservio.ui;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Display-only URL labels. Source URLs remain unchanged for copying and export. */
final class InspectorURL {
    final String origin, path;
    final boolean queryHidden, pathHidden, available;
    InspectorURL(String source) {
        URI parts;
        try { parts = new URI(source.replace("[redacted]", "%5Bredacted%5D")); } catch (URISyntaxException invalid) { parts = null; }
        if (parts == null || parts.getHost() == null || parts.getScheme() == null) {
            origin = ""; path = source; queryHidden = false; pathHidden = false; available = false; return;
        }
        int port = parts.getPort(); String scheme = parts.getScheme().toLowerCase(Locale.ROOT);
        int defaultPort = scheme.equals("https") || scheme.equals("wss") ? 443 : 80;
        String host = parts.getHost();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
        origin = host.toLowerCase(Locale.ROOT) + (port >= 0 && port != defaultPort ? ":" + port : "");
        String rawPath = parts.getRawPath(); if (rawPath == null || rawPath.isEmpty()) rawPath = "/";
        String query = parts.getRawQuery();
        pathHidden = mask(rawPath).equals("/[redacted]"); queryHidden = query != null && mask(query).equals("[redacted]");
        path = readable(rawPath) + (query == null || queryHidden ? "" : "?" + readable(query)); available = true;
    }
    private static String mask(String text) { return text.replaceAll("(?i)%5b", "[").replaceAll("(?i)%5d", "]"); }
    private static int escaped(String text, int index) {
        if (index + 2 >= text.length() || text.charAt(index) != '%') return -1;
        int high = Character.digit(text.charAt(index + 1), 16), low = Character.digit(text.charAt(index + 2), 16);
        return high < 0 || low < 0 ? -1 : high * 16 + low;
    }
    /** One pass: encoded delimiters, controls and format characters are preserved. */
    static String readable(String text) {
        StringBuilder result = new StringBuilder(text.length()); int index = 0;
        while (index < text.length()) {
            int first = escaped(text, index);
            if (first < 0) { result.append(text.charAt(index++)); continue; }
            if (first >= 'a' && first <= 'z' || first >= 'A' && first <= 'Z' || first >= '0' && first <= '9' || "-._~".indexOf(first) >= 0) {
                result.append((char) first); index += 3; continue;
            }
            int length = first >= 0xC2 && first <= 0xDF ? 2 : first >= 0xE0 && first <= 0xEF ? 3 : first >= 0xF0 && first <= 0xF4 ? 4 : 0;
            if (length > 0) {
                byte[] bytes = new byte[length]; boolean complete = true;
                for (int offset = 0; offset < length; offset++) {
                    int value = escaped(text, index + offset * 3); if (value < 0) { complete = false; break; } bytes[offset] = (byte) value;
                }
                if (complete) try {
                    String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                    int type = Character.getType(value.codePointAt(0));
                    if (value.codePointCount(0, value.length()) == 1 && type != Character.CONTROL && type != Character.FORMAT
                        && type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR && type != Character.SURROGATE && type != Character.UNASSIGNED) {
                        result.append(value); index += length * 3; continue;
                    }
                } catch (CharacterCodingException invalid) { /* Keep the original escape sequence. */ }
            }
            result.append(text, index, index + 3); index += 3;
        }
        return result.toString();
    }
}
