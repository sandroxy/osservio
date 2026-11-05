package com.sandrox.osservio.core;

/** Raw input for admission. This type alone does not establish that a header is safe. */
public final class HTTPHeader {
    public final String name;
    public final String value;
    public HTTPHeader(String name, String value) { this.name = name; this.value = value; }
}
