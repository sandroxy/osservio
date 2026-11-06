package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared bounded selection for immutable log and network attachments. */
final class IncidentWindow {
    interface Size<T> { int bytes(T value); }
    interface Identity<T> { String id(T value); }
    private IncidentWindow() { }
    static <T> List<T> select(List<T> events, int count, int bytes, Size<T> size) {
        if (count < 1 || count > 2000 || bytes < 64 || bytes > 1048576) throw new IllegalArgumentException("Invalid incident attachment limit");
        List<T> selected = new ArrayList<>(); int retained = 0;
        for (int index = events.size() - 1; index >= 0; index--) {
            T event = events.get(index); if (event == null) throw EvidenceValidation.invalid();
            int amount = size.bytes(event);
            if (selected.size() == count || amount > bytes - retained) break;
            selected.add(event); retained += amount;
        }
        Collections.reverse(selected); return selected;
    }
    static <T> int validate(List<T> events, int sourceCount, long evicted, Size<T> size, Identity<T> identity) {
        if (events == null || events.size() > 2000 || sourceCount < events.size() || evicted < 0) throw EvidenceValidation.invalid();
        Set<String> identifiers = new HashSet<>(); int bytes = 0;
        for (T event : events) {
            if (event == null || !identifiers.add(identity.id(event)) || size.bytes(event) > 1048576 - bytes) throw EvidenceValidation.invalid();
            bytes += size.bytes(event);
        }
        return bytes;
    }
    static int payloadBytes(Map<String, IncidentPayload> payloads, int maximumCount) {
        if (payloads == null || payloads.size() > maximumCount) throw EvidenceValidation.invalid();
        Set<String> identifiers = new HashSet<>(); int bytes = 0;
        for (Map.Entry<String, IncidentPayload> item : payloads.entrySet()) {
            IncidentPayload payload = item.getValue();
            if (payload == null || !payload.id.equals(item.getKey()) || !identifiers.add(payload.id.toLowerCase(Locale.ROOT))
                || payload.textByteCount > 262144 - bytes) throw EvidenceValidation.invalid();
            bytes += payload.textByteCount;
        }
        return bytes;
    }
}
