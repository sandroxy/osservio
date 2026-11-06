package com.sandrox.osservio.store;

import static com.sandrox.osservio.store.IncidentNames.name;
import com.sandrox.osservio.core.SystemDiagnostic;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Private acknowledgement format; never used as an incident or real-world failure identity. */
final class DiagnosticFingerprint {
    private DiagnosticFingerprint() { }
    static String make(SystemDiagnostic value) {
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
