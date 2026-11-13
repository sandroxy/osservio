package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.List;

/** Bounded JSON emission for explicit LogValue inputs, not a general object serializer. */
final class LogDetailsEncoding {
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        final LogDetailsReference.State state;
        Failure(LogDetailsReference.State state) { this.state = state; }
    }
    static String encode(LogDetails input, int limit) throws Failure {
        if (input.limited) throw limited();
        List<LogField> fields = new ArrayList<>();
        fields.add(new LogField("arguments", LogValue.array(input.arguments)));
        fields.add(new LogField("attributes", LogValue.object(input.attributes)));
        if (input.error != null) {
            LogError error = input.error;
            if (error.type == null || error.type.isEmpty()) throw invalid();
            List<LogField> values = new ArrayList<>(); add(values, "type", error.type); add(values, "message", error.message);
            add(values, "domain", error.domain); add(values, "code", error.code); add(values, "stackTrace", error.stackTrace);
            fields.add(new LogField("error", LogValue.object(values.toArray(new LogField[0]))));
        }
        if (input.callSite != null) {
            LogCallSite site = input.callSite;
            if (site.file == null || site.file.isEmpty() || site.line != null && site.line < 1) throw invalid();
            List<LogField> values = new ArrayList<>(); add(values, "file", site.file); add(values, "function", site.function);
            if (site.line != null) values.add(new LogField("line", LogValue.integer(site.line)));
            fields.add(new LogField("callSite", LogValue.object(values.toArray(new LogField[0]))));
        }
        Writer writer = new Writer(limit); writer.value(LogValue.object(fields.toArray(new LogField[0])), 0);
        return writer.output.toString();
    }
    private static void add(List<LogField> fields, String key, String value) { if (value != null) fields.add(new LogField(key, LogValue.string(value))); }
    private static Failure limited() { return new Failure(LogDetailsReference.State.LIMITED); }
    private static Failure invalid() { return new Failure(LogDetailsReference.State.INVALID); }
    private static final class Writer {
        final StringBuilder output = new StringBuilder();
        final int limit;
        int bytes, nodes;
        Writer(int limit) { this.limit = limit; }
        void ascii(String value) throws Failure {
            if (value.length() > limit - bytes) throw limited(); output.append(value); bytes += value.length();
        }
        void quoted(String text, boolean name) throws Failure {
            ascii("\""); int count = 0;
            for (int offset = 0; offset < text.length();) {
                int point = text.codePointAt(offset);
                if (point >= 0xD800 && point <= 0xDFFF) throw invalid();
                int size = point < 0x80 ? 1 : point < 0x800 ? 2 : point < 0x10000 ? 3 : 4;
                count += size; if (count > (name ? 256 : 16384)) throw limited();
                if (point == 34) ascii("\\\"");
                else if (point == 92) ascii("\\\\");
                else if (point < 32) {
                    String hex = Integer.toHexString(point); ascii("\\u" + (hex.length() == 1 ? "000" : "00") + hex);
                } else {
                    if (size > limit - bytes) throw limited(); output.appendCodePoint(point); bytes += size;
                }
                offset += Character.charCount(point);
            }
            ascii("\"");
        }
        void value(LogValue input, int depth) throws Failure {
            if (depth > 8 || nodes >= 256) throw limited(); nodes++;
            switch (input.kind) {
                case NULL: ascii("null"); break;
                case BOOLEAN: ascii((Boolean) input.value ? "true" : "false"); break;
                case INTEGER: ascii(Long.toString((Long) input.value)); break;
                case NUMBER:
                    double number = (Double) input.value;
                    if (Double.isNaN(number) || Double.isInfinite(number)) throw invalid();
                    ascii(Double.toString(number)); break;
                case STRING: quoted((String) input.value, false); break;
                case ARRAY:
                    ascii("["); LogValue[] values = (LogValue[]) input.value;
                    for (int i = 0; i < values.length; i++) { if (i > 0) ascii(","); value(values[i], depth + 1); }
                    ascii("]"); break;
                case OBJECT:
                    ascii("{"); LogField[] fields = (LogField[]) input.value;
                    for (int i = 0; i < fields.length; i++) {
                        if (i > 0) ascii(","); quoted(fields[i].name, true); ascii(":"); value(fields[i].value, depth + 1);
                    }
                    ascii("}"); break;
                case LIMITED: throw limited();
                default: throw invalid();
            }
        }
    }
}
