package com.sandrox.osservio.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HTTPPrivacyPolicy {
    public enum BodyPolicy {
        OMIT,
        /** Capture supported UTF-8 content within the budget and host text policy. */
        PUBLIC_UTF8
    }
    public final boolean publicURLPath;
    public final boolean publicURLQuery;
    /** Null captures all valid headers; an explicit set selects values without forbidding any name. */
    public final Set<String> publicHeaderNames;
    public final BodyPolicy bodyPolicy;
    public final int bodyByteLimit;
    public final int headerValueByteLimit;
    public final DiagnosticTextPolicy textPolicy;

    public HTTPPrivacyPolicy() { this(true, null, BodyPolicy.PUBLIC_UTF8, 16384, true, 4096, DiagnosticTextPolicy.UNCHANGED); }
    public HTTPPrivacyPolicy(boolean publicURLPath, Set<String> publicHeaderNames, BodyPolicy bodyPolicy, int bodyByteLimit) {
        this(publicURLPath, publicHeaderNames, bodyPolicy, bodyByteLimit, true, 4096, DiagnosticTextPolicy.UNCHANGED);
    }
    public HTTPPrivacyPolicy(boolean publicURLPath, Set<String> publicHeaderNames, BodyPolicy bodyPolicy, int bodyByteLimit,
                             boolean publicURLQuery, int headerValueByteLimit, DiagnosticTextPolicy textPolicy) {
        if ((publicHeaderNames != null && publicHeaderNames.size() > 32) || bodyPolicy == null || bodyByteLimit < 1 || bodyByteLimit > 65536
            || headerValueByteLimit < 1 || headerValueByteLimit > 16384 || textPolicy == null) throw new IllegalArgumentException("Invalid HTTP policy");
        Set<String> names = publicHeaderNames == null ? null : new HashSet<>();
        if (publicHeaderNames != null) for (String name : publicHeaderNames) {
            if (!validHeaderName(name)) throw new IllegalArgumentException("Invalid HTTP header name");
            names.add(name.toLowerCase(Locale.ROOT));
        }
        this.publicURLPath = publicURLPath; this.publicURLQuery = publicURLQuery;
        this.publicHeaderNames = names == null ? null : Collections.unmodifiableSet(names);
        this.bodyPolicy = bodyPolicy; this.bodyByteLimit = bodyByteLimit;
        this.headerValueByteLimit = headerValueByteLimit; this.textPolicy = textPolicy;
    }

    String url(String raw) {
        if (!EvidenceValidation.text(raw, 4096) || !EvidenceValidation.url(raw) || "[unavailable]".equals(raw)) return "[unavailable]";
        try {
            URI parts = new URI(raw.replace("[redacted]", "%5Bredacted%5D"));
            String selected = raw;
            if (!publicURLPath || !publicURLQuery) {
                selected = parts.getScheme() + "://" + parts.getRawAuthority()
                    + (publicURLPath ? parts.getRawPath() : "/%5Bredacted%5D")
                    + (parts.getRawQuery() == null ? "" : "?" + (publicURLQuery ? parts.getRawQuery() : "%5Bredacted%5D"))
                    + (parts.getRawFragment() == null ? "" : "#" + parts.getRawFragment());
            }
            if (!EvidenceValidation.text(selected, 4128)) return "[unavailable]";
            String result = DiagnosticTextPolicy.apply(textPolicy, DiagnosticTextPolicy.Field.URL, null, selected, false, 4128);
            return EvidenceValidation.url(result) ? result : "[unavailable]";
        } catch (URISyntaxException invalid) { return "[unavailable]"; }
    }

    String method(String raw) { return EvidenceValidation.method(raw) ? raw : "OTHER"; }

    static final class Headers {
        final List<HTTPHeader> values;
        final boolean limited;
        Headers(List<HTTPHeader> values, boolean limited) { this.values = values; this.limited = limited; }
    }
    Headers headers(List<HTTPHeader> raw) {
        List<HTTPHeader> result = new ArrayList<>();
        if (raw == null) return new Headers(result, true);
        boolean limited = raw.size() > 32;
        for (int i = 0; i < Math.min(raw.size(), 32); i++) {
            HTTPHeader header = raw.get(i);
            if (header == null || !validHeaderName(header.name) || header.value == null) { limited = true; continue; }
            String name = header.name.toLowerCase(Locale.ROOT);
            BoundedText bounded = BoundedText.prefix(header.value, headerValueByteLimit);
            if (bounded == null) { limited = true; continue; }
            limited |= bounded.truncated;
            String value = publicHeaderNames == null || publicHeaderNames.contains(name)
                ? DiagnosticTextPolicy.apply(textPolicy, DiagnosticTextPolicy.Field.HEADER, name, bounded.text, bounded.truncated, headerValueByteLimit) : null;
            result.add(new HTTPHeader(name, value == null ? "[redacted]" : value));
        }
        return new Headers(result, limited);
    }

    boolean supportsBody(List<HTTPHeader> headers, boolean decoded) {
        if (bodyPolicy != BodyPolicy.PUBLIC_UTF8) return false;
        String contentType = first(headers, "content-type");
        String type = mediaType(contentType);
        if (type == null) return false;
        String[] parameters = contentType.toLowerCase(Locale.ROOT).split(";");
        for (int i = 1; i < parameters.length; i++) {
            String[] pair = parameters[i].split("=", 2);
            if (pair[0].trim().equals("charset") && (pair.length != 2 || !Arrays.asList("utf-8", "us-ascii", "\"utf-8\"", "\"us-ascii\"").contains(pair[1].trim()))) return false;
        }
        String encoding = first(headers, "content-encoding");
        if (!decoded && encoding != null && !encoding.equalsIgnoreCase("identity")) return false;
        return type.startsWith("text/") || type.equals("application/json") || type.endsWith("+json") || type.equals("application/xml")
            || type.endsWith("+xml") || type.equals("application/x-www-form-urlencoded");
    }

    private static String first(List<HTTPHeader> headers, String name) {
        if (headers == null) return null;
        for (int i = 0; i < Math.min(headers.size(), 32); i++) {
            HTTPHeader header = headers.get(i);
            if (header != null && validHeaderName(header.name) && name.equalsIgnoreCase(header.name)) return header.value;
        }
        return null;
    }

    private static String mediaType(String raw) {
        if (raw == null || raw.length() > 256) return null;
        String type = raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return type.matches("[a-z0-9.+-]+/[a-z0-9.+-]+") ? type : null;
    }

    private static boolean validHeaderName(String value) { return EvidenceValidation.headerName(value); }

    static int completeUTF8Prefix(byte[] bytes, int count) {
        if (count == 0) return 0;
        int index = count - 1;
        while (index > Math.max(0, count - 4) && (bytes[index] & 0xC0) == 0x80) index--;
        int first = bytes[index] & 0xFF;
        int expected = first >= 0xC2 && first <= 0xDF ? 2 : first >= 0xE0 && first <= 0xEF ? 3 : first >= 0xF0 && first <= 0xF4 ? 4 : 1;
        if (count - index > 1) {
            int second = bytes[index + 1] & 0xFF;
            if ((first == 0xE0 && second < 0xA0) || (first == 0xED && second > 0x9F)
                || (first == 0xF0 && second < 0x90) || (first == 0xF4 && second > 0x8F)) return count;
        }
        return expected > count - index ? index : count;
    }

    static String decode(byte[] bytes, int count) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, count)).toString(); }
        catch (CharacterCodingException invalid) { return null; }
    }
}
