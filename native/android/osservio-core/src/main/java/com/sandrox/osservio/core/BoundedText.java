package com.sandrox.osservio.core;

/** Bounded scalar-preserving text inspection; never scans the uncaptured suffix. */
final class BoundedText {
    final String text;
    final boolean truncated;
    private BoundedText(String text, boolean truncated) { this.text = text; this.truncated = truncated; }
    static BoundedText prefix(String value, int limit) {
        if (value == null) return null;
        int offset = 0;
        int bytes = 0;
        while (offset < value.length()) {
            int point = value.codePointAt(offset);
            if (point >= 0xD800 && point <= 0xDFFF) return null;
            int size = point <= 0x7F ? 1 : point <= 0x7FF ? 2 : point <= 0xFFFF ? 3 : 4;
            if (bytes > limit - size) break;
            bytes += size;
            offset += Character.charCount(point);
        }
        return new BoundedText(value.substring(0, offset), offset < value.length());
    }
}
