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
        /** The host has reviewed every body handled by this recorder as safe to retain and display. */
        PUBLIC_UTF8
    }
    public final boolean publicURLPath;
    public final Set<String> publicHeaderNames;
    public final BodyPolicy bodyPolicy;
    public final int bodyByteLimit;
    private static final Set<String> SECRET = new HashSet<>(Arrays.asList("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key"));
    private static final Set<String> KNOWN = new HashSet<>(Arrays.asList("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key",
        "content-type", "content-length", "content-encoding", "accept", "accept-encoding", "cache-control", "connection", "host", "user-agent",
        "location", "referer", "origin", "etag", "if-none-match"));

    public HTTPPrivacyPolicy() { this(false, Collections.emptySet(), BodyPolicy.OMIT, 16384); }
    public HTTPPrivacyPolicy(boolean publicURLPath, Set<String> publicHeaderNames, BodyPolicy bodyPolicy, int bodyByteLimit) {
        if (publicHeaderNames == null || publicHeaderNames.size() > 16 || bodyPolicy == null || bodyByteLimit < 1 || bodyByteLimit > 65536) {
            throw new IllegalArgumentException("Invalid HTTP privacy policy");
        }
        Set<String> names = new HashSet<>();
        for (String name : publicHeaderNames) {
            if (!validHeaderName(name) || SECRET.contains(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Invalid public HTTP header");
            names.add(name.toLowerCase(Locale.ROOT));
        }
        this.publicURLPath = publicURLPath; this.publicHeaderNames = Collections.unmodifiableSet(names);
        this.bodyPolicy = bodyPolicy; this.bodyByteLimit = bodyByteLimit;
    }

    String url(String raw) {
        if (raw == null || raw.length() > 4096 || raw.getBytes(StandardCharsets.UTF_8).length > 4096) return "[unavailable]";
        for (int i = 0; i < raw.length(); i++) if (raw.charAt(i) < 0x20 || raw.charAt(i) == 0x7F) return "[unavailable]";
        try {
            URI parts = new URI(raw);
            String scheme = parts.getScheme() == null ? "" : parts.getScheme().toLowerCase(Locale.ROOT);
            String host = parts.getHost();
            if (!Arrays.asList("http", "https", "ws", "wss").contains(scheme) || host == null || host.isEmpty()
                || parts.getPort() == 0 || parts.getPort() > 65535) return "[unavailable]";
            URI ascii = new URI(parts.toASCIIString());
            String origin = scheme + "://" + host.toLowerCase(Locale.ROOT) + (parts.getPort() < 0 ? "" : ":" + parts.getPort());
            String path = publicURLPath ? (ascii.getRawPath().isEmpty() ? "/" : ascii.getRawPath()) : "/[redacted]";
            return origin + path + (parts.getRawQuery() == null ? "" : "?[redacted]");
        } catch (URISyntaxException invalid) { return "[unavailable]"; }
    }

    String method(String raw) {
        if (raw == null || raw.length() > 16) return "OTHER";
        String value = raw.toUpperCase(Locale.ROOT);
        return Arrays.asList("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH").contains(value) ? value : "OTHER";
    }

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
            if (!KNOWN.contains(name) && !publicHeaderNames.contains(name)) { limited = true; continue; }
            String value = "[redacted]";
            if (name.equals("content-type")) { String type = mediaType(header.value); value = type == null ? "[unavailable]" : type; }
            else if (name.equals("content-length") && header.value.length() <= 19 && header.value.matches("[0-9]+")) {
                try { value = Long.toString(Long.parseLong(header.value)); }
                catch (NumberFormatException overflow) { value = "[redacted]"; }
            } else if (publicHeaderNames.contains(name)) {
                value = prefixText(header.value, 512);
                if (header.value.length() > 512 || header.value.getBytes(StandardCharsets.UTF_8).length > 512) limited = true;
            }
            result.add(new HTTPHeader(name, value));
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

    private static boolean validHeaderName(String value) {
        return value != null && !value.isEmpty() && value.length() <= 64 && value.matches("[A-Za-z0-9-]+");
    }

    private static String prefixText(String value, int limit) {
        String prefix = value.substring(0, Math.min(value.length(), limit + 2));
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 == prefix.length()) {
                    if (prefix.length() == value.length()) return "[unavailable]";
                    prefix = prefix.substring(0, i); break;
                }
                if (!Character.isLowSurrogate(prefix.charAt(++i))) return "[unavailable]";
            } else if (Character.isLowSurrogate(c)) return "[unavailable]";
        }
        byte[] bytes = prefix.getBytes(StandardCharsets.UTF_8);
        int count = Math.min(bytes.length, limit);
        if (count < bytes.length) count = completeUTF8Prefix(bytes, count);
        String result = decode(bytes, count);
        return result == null ? "[unavailable]" : result;
    }

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
