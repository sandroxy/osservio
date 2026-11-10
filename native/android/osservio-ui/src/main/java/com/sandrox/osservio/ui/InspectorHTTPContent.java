package com.sandrox.osservio.ui;

import com.sandrox.osservio.core.HTTPHeader;
import java.util.List;
import java.util.Locale;

/** Classifies observed response Content-Type, never URL extensions or request content. */
final class InspectorHTTPContent {
    static final String[] CATEGORIES = {"json", "text", "image", "media", "other", "unknown"};
    private InspectorHTTPContent() { }
    static String category(List<HTTPHeader> headers) {
        String type = null;
        for (HTTPHeader header : headers) {
            if (!header.name.equalsIgnoreCase("content-type")) continue;
            int separator = header.value.indexOf(';');
            String current = (separator < 0 ? header.value : header.value.substring(0, separator)).trim().toLowerCase(Locale.ROOT);
            if (type != null && !type.equals(current)) return "unknown";
            type = current;
        }
        if (type == null || type.indexOf('*') >= 0) return "unknown";
        String[] parts = type.split("/", -1);
        if (parts.length != 2 || !token(parts[0]) || !token(parts[1])) return "unknown";
        if (type.equals("application/json") || type.equals("text/json") || parts[1].endsWith("+json")) return "json";
        if (parts[0].equals("image")) return "image";
        if (parts[0].equals("text") || type.equals("application/xml") || parts[1].endsWith("+xml")) return "text";
        if (parts[0].equals("audio") || parts[0].equals("video")) return "media";
        return "other";
    }
    private static boolean token(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && "!#$%&'*+-.^_`|~".indexOf(c) < 0) return false;
        }
        return true;
    }
}
