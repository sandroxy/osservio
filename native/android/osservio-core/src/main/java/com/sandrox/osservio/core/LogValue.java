package com.sandrox.osservio.core;

import java.util.Arrays;
import java.util.Objects;

/** Explicit immutable values. No arbitrary object reflection, getters, or toString calls. */
public final class LogValue {
    enum Kind { NULL, BOOLEAN, INTEGER, NUMBER, STRING, ARRAY, OBJECT, LIMITED }
    final Kind kind;
    final Object value;
    private LogValue(Kind kind, Object value) { this.kind = kind; this.value = value; }
    public static LogValue nullValue() { return new LogValue(Kind.NULL, null); }
    public static LogValue bool(boolean value) { return new LogValue(Kind.BOOLEAN, value); }
    public static LogValue integer(long value) { return new LogValue(Kind.INTEGER, value); }
    public static LogValue number(double value) { return new LogValue(Kind.NUMBER, value); }
    public static LogValue string(String value) { return new LogValue(Kind.STRING, Objects.requireNonNull(value, "value")); }
    public static LogValue array(LogValue... values) {
        Objects.requireNonNull(values, "values");
        if (values.length > 64) return new LogValue(Kind.LIMITED, null);
        LogValue[] copy = Arrays.copyOf(values, values.length);
        for (LogValue value : copy) Objects.requireNonNull(value, "value");
        return new LogValue(Kind.ARRAY, copy);
    }
    /** Ordered fields preserve duplicate names, matching the native JSON reader. */
    public static LogValue object(LogField... fields) {
        Objects.requireNonNull(fields, "fields");
        if (fields.length > 64) return new LogValue(Kind.LIMITED, null);
        LogField[] copy = Arrays.copyOf(fields, fields.length);
        for (LogField field : copy) Objects.requireNonNull(field, "field");
        return new LogValue(Kind.OBJECT, copy);
    }
}
