package com.sandrox.osservio.ui;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ordered fields with duplicate names preserved; the original source is never rewritten. */
final class InspectorFormDocument {
    interface Cancellation { boolean isCancelled(); }
    enum Failure { INVALID, LIMITED, CANCELLED }
    static final class ParseFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final Failure reason;
        ParseFailure(Failure reason) { super(reason.name()); this.reason = reason; }
    }
    static final class Field {
        final String name, value, original;
        final boolean hasEquals;
        final int start, end;
        Field(String name, String value, String original, boolean hasEquals, int start, int end) {
            this.name = name; this.value = value; this.original = original; this.hasEquals = hasEquals; this.start = start; this.end = end;
        }
    }
    final List<Field> fields;
    final String searchText;
    private InspectorFormDocument(List<Field> fields, String searchText) { this.fields = Collections.unmodifiableList(fields); this.searchText = searchText; }
    static InspectorFormDocument parse(String source, Cancellation cancelled) throws ParseFailure {
        if (source.length() > 65_536) throw new ParseFailure(Failure.LIMITED);
        for (int i = 0; i < source.length(); i++) {
            if (i % 256 == 0 && cancelled.isCancelled()) throw new ParseFailure(Failure.CANCELLED);
            char value = source.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (++i == source.length() || !Character.isLowSurrogate(source.charAt(i))) throw new ParseFailure(Failure.INVALID);
            } else if (Character.isLowSurrogate(value)) throw new ParseFailure(Failure.INVALID);
        }
        List<Field> fields = new ArrayList<>(); StringBuilder search = new StringBuilder(); int start = 0;
        for (int end = 0; end <= source.length(); end++) {
            if (end != source.length() && source.charAt(end) != '&') continue;
            if (cancelled.isCancelled()) throw new ParseFailure(Failure.CANCELLED);
            int from = start; start = end + 1; if (end == from) continue;
            if (fields.size() == 256) throw new ParseFailure(Failure.LIMITED);
            int equal = source.indexOf('=', from); if (equal >= end) equal = -1;
            String name = decode(source.substring(from, equal < 0 ? end : equal), cancelled);
            String value = decode(source.substring(equal < 0 ? end : equal + 1, end), cancelled);
            int offset = search.length(); search.append(name).append('\n').append(value).append('\n');
            fields.add(new Field(name, value, source.substring(from, end), equal >= 0, offset, search.length()));
        }
        return new InspectorFormDocument(fields, search.toString());
    }
    int fieldAt(int offset) {
        for (int i = 0; i < fields.size(); i++) if (offset >= fields.get(i).start && offset < fields.get(i).end) return i;
        return -1;
    }
    private static String decode(String source, Cancellation cancelled) throws ParseFailure {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8), result = new byte[bytes.length]; int count = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (i % 256 == 0 && cancelled.isCancelled()) throw new ParseFailure(Failure.CANCELLED);
            int value = bytes[i] & 255;
            if (value == '%') {
                if (i + 2 >= bytes.length) throw new ParseFailure(Failure.INVALID);
                int high = hex(bytes[++i]), low = hex(bytes[++i]);
                if (high < 0 || low < 0) throw new ParseFailure(Failure.INVALID);
                result[count++] = (byte) (high * 16 + low);
            } else result[count++] = (byte) (value == '+' ? ' ' : value);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(result, 0, count)).toString();
        } catch (CharacterCodingException invalid) { throw new ParseFailure(Failure.INVALID); }
    }
    private static int hex(byte raw) {
        int value = raw & 255;
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        return -1;
    }
}
