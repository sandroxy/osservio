package com.sandrox.osservio.ui;

import com.sandrox.osservio.core.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A bounded reading window from already retained evidence, never a live subscription. */
final class InspectorLogContext {
    static final int SIDE_LIMIT = 20;
    static final int TEXT_BYTE_LIMIT = 131072;
    final List<LogEvent> events;
    final int targetIndex;

    private InspectorLogContext(List<LogEvent> events, int targetIndex) {
        this.events = Collections.unmodifiableList(events); this.targetIndex = targetIndex;
    }

    static InspectorLogContext select(List<LogEvent> source, LogEvent target) {
        int anchor = -1;
        for (int index = 0; index < source.size(); index++) {
            LogEvent event = source.get(index);
            if (event.sequence == target.sequence && event.sessionId.equalsIgnoreCase(target.sessionId)) { anchor = index; break; }
        }
        if (anchor < 0) return null;
        List<LogEvent> selected = new ArrayList<>();
        int bytes = target.textByteCount, beforeBudget = (TEXT_BYTE_LIMIT - bytes) / 2;
        for (int index = anchor - 1; index >= 0 && selected.size() < SIDE_LIMIT; index--) {
            LogEvent event = source.get(index);
            if (!event.sessionId.equalsIgnoreCase(target.sessionId)) continue;
            if (event.textByteCount > beforeBudget) break;
            selected.add(event); beforeBudget -= event.textByteCount; bytes += event.textByteCount;
        }
        Collections.reverse(selected);
        int targetIndex = selected.size(); selected.add(target);
        for (int index = anchor + 1, after = 0; index < source.size() && after < SIDE_LIMIT; index++) {
            LogEvent event = source.get(index);
            if (!event.sessionId.equalsIgnoreCase(target.sessionId)) continue;
            if (event.textByteCount > TEXT_BYTE_LIMIT - bytes) break;
            selected.add(event); bytes += event.textByteCount; after++;
        }
        return new InspectorLogContext(selected, targetIndex);
    }
}
