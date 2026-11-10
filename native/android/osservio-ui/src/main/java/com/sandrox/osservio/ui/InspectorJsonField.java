package com.sandrox.osservio.ui;

import java.util.ArrayList;
import java.util.List;

/** Exact scalar lookup; duplicate keys are ambiguous, and numeric IDs are never rounded. */
final class InspectorJsonField {
    enum Match { YES, NO, UNAVAILABLE }
    private InspectorJsonField() { }
    static List<String> components(String pointer) {
        if (pointer.length() > 256 || !pointer.startsWith("/")) return null;
        String[] parts = pointer.substring(1).split("/", -1);
        if (parts.length > 16) return null;
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            StringBuilder decoded = new StringBuilder();
            for (int i = 0; i < part.length(); i++) {
                char value = part.charAt(i);
                if (value != '~') decoded.append(value);
                else {
                    if (++i == part.length()) return null;
                    char escape = part.charAt(i); if (escape != '0' && escape != '1') return null;
                    decoded.append(escape == '0' ? '~' : '/');
                }
            }
            result.add(decoded.toString());
        }
        return result;
    }
    static Match matches(String source, String pointer, String value, InspectorJsonDocument.Cancellation cancelled) {
        List<String> parts = components(pointer); if (parts == null) return Match.UNAVAILABLE;
        final InspectorJsonDocument document;
        try { document = InspectorJsonDocument.parse(source, cancelled); }
        catch (InspectorJsonDocument.ParseFailure failure) { return Match.UNAVAILABLE; }
        int index = 0;
        for (String part : parts) {
            if (cancelled.isCancelled()) return Match.UNAVAILABLE;
            InspectorJsonDocument.Node node = document.nodes.get(index);
            if (node.kind == InspectorJsonDocument.Kind.OBJECT) {
                int found = -1;
                for (int child : node.children) {
                    InspectorJsonDocument.Node entry = document.nodes.get(child);
                    if (part.equals(decode(source.substring(entry.keyStart, entry.keyEnd)))) {
                        if (found >= 0) return Match.UNAVAILABLE;
                        found = child;
                    }
                }
                if (found < 0) return Match.NO; index = found;
            } else if (node.kind == InspectorJsonDocument.Kind.ARRAY) {
                if (part.isEmpty() || part.length() > 1 && part.startsWith("0")) return Match.NO;
                int offset = 0;
                for (int i = 0; i < part.length(); i++) {
                    char digit = part.charAt(i);
                    if (digit < '0' || digit > '9' || offset > InspectorJsonDocument.MAX_NODES) return Match.NO;
                    offset = offset * 10 + digit - '0';
                }
                if (offset >= node.children.size()) return Match.NO;
                index = node.children.get(offset);
            } else return Match.NO;
        }
        InspectorJsonDocument.Node node = document.nodes.get(index);
        if (node.kind == InspectorJsonDocument.Kind.OBJECT || node.kind == InspectorJsonDocument.Kind.ARRAY) return Match.NO;
        String original = document.original(index);
        String scalar = node.kind == InspectorJsonDocument.Kind.STRING ? decode(original) : original;
        return scalar == null ? Match.UNAVAILABLE : scalar.equals(value) ? Match.YES : Match.NO;
    }
    private static String decode(String token) {
        StringBuilder result = new StringBuilder();
        for (int i = 1; i < token.length() - 1; i++) {
            char value = token.charAt(i);
            if (value != '\\') result.append(value);
            else {
                char escape = token.charAt(++i);
                switch (escape) {
                    case 'u':
                        result.append((char) Integer.parseInt(token.substring(i + 1, i + 5), 16)); i += 4; break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    default: result.append(escape); break;
                }
            }
        }
        for (int i = 0; i < result.length(); i++) {
            char unit = result.charAt(i);
            if (Character.isHighSurrogate(unit)) {
                if (++i == result.length() || !Character.isLowSurrogate(result.charAt(i))) return null;
            } else if (Character.isLowSurrogate(unit)) return null;
        }
        return result.toString();
    }
}
