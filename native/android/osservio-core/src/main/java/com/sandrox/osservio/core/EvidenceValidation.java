package com.sandrox.osservio.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates persisted evidence without repairing it or reclassifying raw input as public. */
final class EvidenceValidation {
    private static final Set<String> SECRET_HEADERS = new HashSet<>(Arrays.asList("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key"));
    private EvidenceValidation() { }
    static boolean id(String value) {
        if (value == null || value.length() != 36) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) { if (c != '-') return false; }
            else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }
    static boolean text(String value, int limit) {
        if (value == null || value.length() > limit) return false;
        BoundedText checked = BoundedText.prefix(value, limit);
        return checked != null && !checked.truncated;
    }
    static boolean label(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_')) return false;
        }
        return true;
    }
    static boolean url(String value) {
        if ("[unavailable]".equals(value)) return true;
        // Raw admission is 4,096 bytes; replacing a short path/query can add placeholder bytes.
        if (!text(value, 4128)) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) == 127) return false;
        int query = value.indexOf('?');
        if (query >= 0 && !value.substring(query + 1).equals("[redacted]")) return false;
        try {
            // The displayed redaction marker has literal brackets, which URI requires escaped in paths.
            URI parts = new URI(value.replace("[redacted]", "%5Bredacted%5D"));
            String scheme = parts.getScheme(), host = parts.getHost();
            return Arrays.asList("http", "https", "ws", "wss").contains(scheme) && host != null && !host.isEmpty()
                && parts.getUserInfo() == null && parts.getFragment() == null
                && (parts.getPort() == -1 || (parts.getPort() >= 1 && parts.getPort() <= 65535));
        } catch (URISyntaxException failure) { return false; }
    }
    static boolean headers(List<HTTPHeader> values) {
        if (values == null || values.size() > 32) return false;
        for (HTTPHeader header : values) {
            if (header == null || header.name == null || header.name.isEmpty() || header.name.length() > 64 || !text(header.value, 512)) return false;
            for (int i = 0; i < header.name.length(); i++) {
                char c = header.name.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) return false;
            }
            if (SECRET_HEADERS.contains(header.name) && !header.value.equals("[redacted]")) return false;
        }
        return true;
    }
    static boolean httpBodyId(String value) {
        if (value == null || (value.length() != 44 && value.length() != 45)) return false;
        String identifier = value.substring(0, 36);
        return id(identifier) && (value.equals(identifier + ".request") || value.equals(identifier + ".response"));
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid restored evidence"); }
}
