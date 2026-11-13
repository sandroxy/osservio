package com.sandrox.osservio.core;

import java.util.Objects;

public final class LogField {
    public final String name;
    public final LogValue value;
    public LogField(String name, LogValue value) {
        this.name = Objects.requireNonNull(name, "name"); this.value = Objects.requireNonNull(value, "value");
    }
}
