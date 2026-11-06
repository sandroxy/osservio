package com.sandrox.osservio.core;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Reviewed system facts only. Never accepts exception messages, user context, or raw stack text. */
public final class SystemDiagnostic {
    public enum Source { ANDROID_EXIT_INFO, METRIC_KIT, APPLE_SIGNAL }
    public enum Kind { MANAGED_CRASH, NATIVE_CRASH, ANR, LOW_MEMORY_TERMINATION, SIGNAL_TERMINATION, OTHER_EXIT, CRASH, HANG }
    public enum MetadataKey { APP_VERSION, APP_BUILD, OS_VERSION, DEVICE_MODEL }
    public enum TombstoneState { NOT_REQUESTED, NOT_APPLICABLE, UNSUPPORTED, UNAVAILABLE, INVALID, TOO_LARGE, LIMIT_REACHED, AVAILABLE }
    public static final class Text {
        public final String value;
        public final boolean isTruncated;
        private Text(String value, boolean truncated) { this.value = value; isTruncated = truncated; }
        /** Only public application/build/system metadata belongs here. */
        public static Text capturePublic(String value) {
            Objects.requireNonNull(value, "value");
            BoundedText safe = BoundedText.prefix(value, 256);
            if (value.isEmpty() || safe == null) throw new IllegalArgumentException("Invalid diagnostic metadata");
            return new Text(safe.text, safe.truncated);
        }
        public static Text restore(String value, boolean truncated) {
            Text checked = capturePublic(value);
            if (checked.isTruncated) throw new IllegalArgumentException("Oversized diagnostic metadata");
            return new Text(value, truncated);
        }
    }
    public static final class AndroidExit {
        public final int reasonCode, status, processID, importance;
        /** Last system sample, not memory measured at termination. Null means unavailable. */
        public final Long pssKiB, rssKiB;
        public final TombstoneState tombstoneState;
        public final AndroidTombstone tombstone;
        private AndroidExit(int reasonCode, int status, int processID, int importance, Long pssKiB, Long rssKiB,
                            TombstoneState tombstoneState, AndroidTombstone tombstone) {
            this.reasonCode = reasonCode; this.status = status; this.processID = processID;
            this.importance = importance; this.pssKiB = pssKiB; this.rssKiB = rssKiB;
            this.tombstoneState = tombstoneState; this.tombstone = tombstone;
        }
    }
    public static final class MetricKit {
        public final Map<MetadataKey, Text> metadata;
        public final Integer signal;
        public final Long exceptionType, hangDurationMillis;
        /** Exact canonical decimal, including signed or unsigned 64-bit encodings. */
        public final String exceptionCode;
        private MetricKit(Map<MetadataKey, Text> metadata, Integer signal, Long exceptionType, String exceptionCode, Long duration) {
            this.metadata = metadata; this.signal = signal; this.exceptionType = exceptionType;
            this.exceptionCode = exceptionCode; hangDurationMillis = duration;
        }
    }
    public final Source source;
    public final Kind kind;
    /** Android supplies a point; MetricKit supplies a reporting interval, not an exact crash time. */
    public final long intervalStartMillis, intervalEndMillis;
    public final AndroidExit androidExit;
    public final MetricKit metricKit;
    public final AppleSignalCrash appleSignal;
    private SystemDiagnostic(Source source, Kind kind, long start, long end, AndroidExit exit, MetricKit metricKit, AppleSignalCrash appleSignal) {
        this.source = source; this.kind = kind; intervalStartMillis = start; intervalEndMillis = end;
        androidExit = exit; this.metricKit = metricKit; this.appleSignal = appleSignal;
    }
    public int textByteCount() {
        int total = 0;
        if (metricKit != null) {
            for (Text text : metricKit.metadata.values()) total += text.value.getBytes(StandardCharsets.UTF_8).length;
            if (metricKit.exceptionCode != null) total += metricKit.exceptionCode.length();
        }
        return total + (appleSignal == null ? 0 : appleSignal.textByteCount()) +
            (androidExit == null || androidExit.tombstone == null ? 0 : androidExit.tombstone.textByteCount());
    }
    public static SystemDiagnostic androidExit(long timestampMillis, int reasonCode, int status, int processID,
                                               int importance, Long pssKiB, Long rssKiB) {
        return androidExit(timestampMillis, reasonCode, status, processID, importance, pssKiB, rssKiB,
            reasonCode == 5 ? TombstoneState.NOT_REQUESTED : TombstoneState.NOT_APPLICABLE, null);
    }
    public static SystemDiagnostic androidExit(long timestampMillis, int reasonCode, int status, int processID,
                                               int importance, Long pssKiB, Long rssKiB,
                                               TombstoneState tombstoneState, AndroidTombstone tombstone) {
        Objects.requireNonNull(tombstoneState, "tombstone state");
        if (timestampMillis < 0 || reasonCode < 0 || processID <= 0 || importance < 0 ||
            (pssKiB != null && pssKiB <= 0) || (rssKiB != null && rssKiB <= 0) ||
            ((tombstoneState == TombstoneState.AVAILABLE) != (tombstone != null)) ||
            ((reasonCode != 5) != (tombstoneState == TombstoneState.NOT_APPLICABLE))) {
            throw new IllegalArgumentException("Invalid process exit evidence");
        }
        Kind kind;
        switch (reasonCode) {
            case 2: kind = Kind.SIGNAL_TERMINATION; break;
            case 3: kind = Kind.LOW_MEMORY_TERMINATION; break;
            case 4: kind = Kind.MANAGED_CRASH; break;
            case 5: kind = Kind.NATIVE_CRASH; break;
            case 6: kind = Kind.ANR; break;
            default: kind = Kind.OTHER_EXIT;
        }
        return new SystemDiagnostic(Source.ANDROID_EXIT_INFO, kind, timestampMillis, timestampMillis,
            new AndroidExit(reasonCode, status, processID, importance, pssKiB, rssKiB, tombstoneState, tombstone), null, null);
    }
    public static SystemDiagnostic metricKit(long startMillis, long endMillis, Kind kind, Map<MetadataKey, Text> metadata,
                                             Integer signal, Long exceptionType, String exceptionCode, Long hangDurationMillis) {
        Objects.requireNonNull(metadata, "metadata");
        if (startMillis > endMillis || (kind != Kind.CRASH && kind != Kind.HANG) ||
            (signal != null && signal <= 0) || (exceptionType != null && exceptionType < 0) ||
            (exceptionCode != null && !validCode(exceptionCode)) ||
            (kind == Kind.CRASH && hangDurationMillis != null) ||
            (kind == Kind.HANG && (signal != null || exceptionType != null || exceptionCode != null ||
                hangDurationMillis == null || hangDurationMillis < 0)) || metadata.size() > MetadataKey.values().length) {
            throw new IllegalArgumentException("Invalid MetricKit evidence");
        }
        Map<MetadataKey, Text> copy = new EnumMap<>(MetadataKey.class);
        for (Map.Entry<MetadataKey, Text> entry : metadata.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "metadata key"), Objects.requireNonNull(entry.getValue(), "metadata value"));
        }
        return new SystemDiagnostic(Source.METRIC_KIT, kind, startMillis, endMillis, null,
            new MetricKit(Collections.unmodifiableMap(copy), signal, exceptionType, exceptionCode, hangDurationMillis), null);
    }
    public static SystemDiagnostic appleSignal(AppleSignalCrash evidence) {
        Objects.requireNonNull(evidence, "evidence");
        return new SystemDiagnostic(Source.APPLE_SIGNAL, Kind.NATIVE_CRASH, evidence.timestampMillis, evidence.timestampMillis,
            null, null, evidence);
    }
    private static boolean validCode(String value) {
        if (value.isEmpty() || value.length() > 20 || !value.matches("0|-?[1-9][0-9]*")) return false;
        BigInteger number = new BigInteger(value);
        return number.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0 &&
            number.compareTo(new BigInteger("18446744073709551615")) <= 0;
    }
}
