package com.sandrox.osservio.store;

import static com.sandrox.osservio.store.IncidentNames.name;
import com.sandrox.osservio.core.AppleSignalCrash;
import com.sandrox.osservio.core.AndroidTombstone;
import com.sandrox.osservio.core.SystemDiagnostic;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Private acknowledgement format; never used as an incident or real-world failure identity. */
final class DiagnosticFingerprint {
    private DiagnosticFingerprint() { }
    static String make(SystemDiagnostic value) { return make(value, true); }
    /** Rich evidence also acknowledges the original summary, so later OS trace eviction cannot add a degraded duplicate. */
    static String summaryAlias(SystemDiagnostic value) {
        return value.androidExit != null && value.androidExit.tombstone != null ? make(value, false) : null;
    }
    private static String make(SystemDiagnostic value, boolean includeTombstone) {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is unavailable", failure); }
        digest.update("osservio.system-diagnostic/1\n".getBytes(StandardCharsets.UTF_8));
        SystemDiagnostic.AndroidExit exit = value.androidExit;
        SystemDiagnostic.MetricKit metric = value.metricKit;
        field(digest, name(value.source)); field(digest, name(value.kind));
        field(digest, Long.toString(value.intervalStartMillis)); field(digest, Long.toString(value.intervalEndMillis));
        field(digest, exit == null ? null : Integer.toString(exit.reasonCode));
        field(digest, exit == null ? null : Integer.toString(exit.status));
        field(digest, exit == null ? null : Integer.toString(exit.processID));
        field(digest, exit == null ? null : Integer.toString(exit.importance));
        field(digest, exit == null || exit.pssKiB == null ? null : exit.pssKiB.toString());
        field(digest, exit == null || exit.rssKiB == null ? null : exit.rssKiB.toString());
        field(digest, metric == null || metric.signal == null ? null : metric.signal.toString());
        field(digest, metric == null || metric.exceptionType == null ? null : metric.exceptionType.toString());
        field(digest, metric == null ? null : metric.exceptionCode);
        field(digest, metric == null || metric.hangDurationMillis == null ? null : metric.hangDurationMillis.toString());
        for (SystemDiagnostic.MetadataKey key : SystemDiagnostic.MetadataKey.values()) {
            SystemDiagnostic.Text text = metric == null ? null : metric.metadata.get(key);
            field(digest, text == null ? null : text.value); field(digest, text == null ? null : text.isTruncated ? "1" : "0");
        }
        AppleSignalCrash crash = value.appleSignal;
        if (crash != null) {
            field(digest, crash.reportID); field(digest, crash.signal.wireName); field(digest, crash.code);
            field(digest, crash.faultAddress); field(digest, crash.threadNumber == null ? null : crash.threadNumber.toString());
            field(digest, crash.imageCacheIncomplete ? "1" : "0"); field(digest, Integer.toString(crash.frames.size()));
            for (String address : crash.frames) field(digest, address);
            field(digest, Integer.toString(crash.images.size()));
            for (AppleSignalCrash.Image image : crash.images) {
                field(digest, image.baseAddress); field(digest, image.size); field(digest, image.uuid);
                field(digest, image.cpuType); field(digest, image.cpuSubtype);
            }
            for (SystemDiagnostic.MetadataKey key : SystemDiagnostic.MetadataKey.values()) {
                SystemDiagnostic.Text text = crash.metadata.get(key);
                field(digest, text == null ? null : text.value); field(digest, text == null ? null : text.isTruncated ? "1" : "0");
            }
        }
        // Retrieval state is not new failure material. Preserve summary-only fingerprints byte-for-byte.
        AndroidTombstone tombstone = exit == null ? null : exit.tombstone;
        if (includeTombstone && tombstone != null) {
            field(digest, "androidTombstone/1"); field(digest, Integer.toString(tombstone.architectureCode));
            field(digest, Integer.toString(tombstone.signalNumber)); field(digest, Integer.toString(tombstone.signalCode));
            field(digest, Integer.toString(tombstone.threadID)); field(digest, tombstone.faultAddress);
            field(digest, Integer.toString(tombstone.frames.size()));
            for (AndroidTombstone.Frame frame : tombstone.frames) {
                field(digest, frame.programCounter); field(digest, frame.relativeProgramCounter); field(digest, frame.buildID);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte octet : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", octet & 255));
        return result.toString();
    }
    private static void field(MessageDigest digest, String value) {
        if (value == null) digest.update("-\n".getBytes(StandardCharsets.UTF_8));
        else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8)); digest.update(bytes); digest.update((byte) 10);
        }
    }
}
