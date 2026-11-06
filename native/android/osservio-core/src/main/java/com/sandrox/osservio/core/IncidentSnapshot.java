package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** A deliberate local problem snapshot, not proof of a crash or its cause. */
public final class IncidentSnapshot {
    public final String id;
    public final long timestampMillis;
    public final Note note;
    /** Retains the environment's own observation time; null means it was not attached. */
    public final EnvironmentSnapshot environment;
    /** Null means no snapshot was attached, not that the app had no activity. */
    public final Breadcrumbs breadcrumbs;
    /** Sanitized text bytes only; excludes structural, indexing, and object overhead. */
    public final int textByteCount;
    private IncidentSnapshot(String id, long timestampMillis, Note note, EnvironmentSnapshot environment, Breadcrumbs breadcrumbs) {
        this.id = id; this.timestampMillis = timestampMillis; this.note = note; this.environment = environment; this.breadcrumbs = breadcrumbs;
        int bytes = note == null ? 0 : note.text.getBytes(StandardCharsets.UTF_8).length;
        if (environment != null) for (EnvironmentSnapshot.Value value : environment.values.values()) {
            if (value.fact.type == EnvironmentSnapshot.Fact.Type.TEXT) bytes += value.fact.text.getBytes(StandardCharsets.UTF_8).length;
        }
        textByteCount = bytes + (breadcrumbs == null ? 0 : breadcrumbs.retainedTextBytes);
    }
    public static IncidentSnapshot capture(LogMessage note, EnvironmentSnapshot environment, BreadcrumbSnapshot breadcrumbs) {
        return capture(note, environment, breadcrumbs, 200, 65536, LogClock.SYSTEM);
    }
    public static IncidentSnapshot capture(LogMessage note, EnvironmentSnapshot environment, BreadcrumbSnapshot breadcrumbs,
                                           int breadcrumbLimit, int breadcrumbTextByteLimit, LogClock clock) {
        if (breadcrumbLimit < 1 || breadcrumbLimit > 2000 || breadcrumbTextByteLimit < 64 || breadcrumbTextByteLimit > 1048576) throw invalid();
        Note safeNote = note == null ? null : Note.capture(note);
        Breadcrumbs context = breadcrumbs == null ? null : Breadcrumbs.capture(breadcrumbs, breadcrumbLimit, breadcrumbTextByteLimit);
        return new IncidentSnapshot(UUID.randomUUID().toString(), Objects.requireNonNull(clock, "clock").wallTimeMillis(), safeNote, environment, context);
    }
    /** Restores previously sanitized evidence without inventing identity or time. */
    public static IncidentSnapshot restore(String id, long timestampMillis, Note note, EnvironmentSnapshot environment, Breadcrumbs breadcrumbs) {
        if (!validId(id)) throw invalid();
        return new IncidentSnapshot(id, timestampMillis, note, environment, breadcrumbs);
    }
    public static final class Note {
        public final String text;
        public final boolean isPrivate;
        public final boolean isTruncated;
        private Note(String text, boolean isPrivate, boolean isTruncated) { this.text = text; this.isPrivate = isPrivate; this.isTruncated = isTruncated; }
        /** Classifies a note independently of the mark time, retaining at most 4,096 UTF-8 bytes. */
        public static Note capture(LogMessage message) {
            BoundedText safe = BoundedText.prefix(message.text, 4096);
            if (safe == null) throw invalid();
            return new Note(safe.text, message.isPrivate, safe.truncated);
        }
        public static Note restore(String text, boolean isPrivate, boolean isTruncated) {
            BoundedText safe = BoundedText.prefix(text, 4096);
            if (safe == null || safe.truncated || (isPrivate && (!"[private]".equals(text) || isTruncated))) throw invalid();
            return new Note(text, isPrivate, isTruncated);
        }
    }
    public static final class Breadcrumbs {
        public final List<BreadcrumbEvent> events;
        public final int sourceEventCount;
        public final long sourceEvictedCount;
        public final int retainedTextBytes;
        public final int omittedEventCount;
        private Breadcrumbs(List<BreadcrumbEvent> events, int sourceEventCount, long sourceEvictedCount, int retainedTextBytes) {
            this.events = Collections.unmodifiableList(events); this.sourceEventCount = sourceEventCount;
            this.sourceEvictedCount = sourceEvictedCount; this.retainedTextBytes = retainedTextBytes; omittedEventCount = sourceEventCount - events.size();
        }
        private static Breadcrumbs capture(BreadcrumbSnapshot source, int count, int bytes) {
            if (source.evictedCount < 0) throw invalid();
            List<BreadcrumbEvent> chosen = new ArrayList<>(); int retained = 0;
            for (int i = source.events.size() - 1; i >= 0; i--) {
                BreadcrumbEvent event = source.events.get(i);
                if (event == null) throw invalid();
                if (chosen.size() >= count || event.textByteCount > bytes - retained) break;
                chosen.add(event); retained += event.textByteCount;
            }
            Collections.reverse(chosen);
            return restore(chosen, source.events.size(), source.evictedCount);
        }
        public static Breadcrumbs restore(List<BreadcrumbEvent> events, int sourceEventCount, long sourceEvictedCount) {
            if (events == null || events.size() > 2000 || sourceEventCount < events.size() || sourceEvictedCount < 0) throw invalid();
            Set<String> identifiers = new HashSet<>(); int bytes = 0;
            for (BreadcrumbEvent event : events) {
                if (event == null || !identifiers.add(event.id.toLowerCase(Locale.ROOT)) || event.textByteCount > 1048576 - bytes) throw invalid();
                bytes += event.textByteCount;
            }
            return new Breadcrumbs(new ArrayList<>(events), sourceEventCount, sourceEvictedCount, bytes);
        }
    }
    private static boolean validId(String id) {
        if (id == null || id.length() != 36) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) { if (c != '-') return false; }
            else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid incident snapshot"); }
}
