package com.sandrox.osservio.core;

import java.util.Arrays;
import java.util.Objects;

/** Details are classified independently from the summary message. Public means authorized local capture. */
public final class LogDetails {
    final LogValue[] arguments;
    final LogField[] attributes;
    final LogError error;
    final LogCallSite callSite;
    final boolean isPrivate;
    final boolean limited;
    private LogDetails(LogValue[] arguments, LogField[] attributes, LogError error, LogCallSite callSite, boolean isPrivate) {
        Objects.requireNonNull(arguments, "arguments"); Objects.requireNonNull(attributes, "attributes");
        limited = arguments.length > 32 || attributes.length > 64;
        this.arguments = limited ? new LogValue[0] : Arrays.copyOf(arguments, arguments.length);
        this.attributes = limited ? new LogField[0] : Arrays.copyOf(attributes, attributes.length);
        for (LogValue value : this.arguments) Objects.requireNonNull(value, "argument");
        for (LogField field : this.attributes) Objects.requireNonNull(field, "attribute");
        this.error = limited ? null : error; this.callSite = limited ? null : callSite; this.isPrivate = isPrivate;
    }
    public static LogDetails publicValues(LogValue[] arguments, LogField[] attributes, LogError error, LogCallSite callSite) {
        return new LogDetails(arguments, attributes, error, callSite, false);
    }
    public static LogDetails privateValues() { return new LogDetails(new LogValue[0], new LogField[0], null, null, true); }
}
