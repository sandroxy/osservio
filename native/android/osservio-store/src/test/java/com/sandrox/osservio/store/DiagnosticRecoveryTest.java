package com.sandrox.osservio.store;

import com.sandrox.osservio.core.AppleSignalCrash;
import com.sandrox.osservio.core.DiagnosticRecoveryReceipt;
import com.sandrox.osservio.core.SystemDiagnostic;
import com.sandrox.osservio.core.SystemDiagnosticBatch;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DiagnosticRecoveryTest {
    @Test public void fingerprintsMatchIndependentVectorsIncludingFramingAndExactNumbers() throws Exception {
        for (String line : Files.readAllLines(Paths.get(System.getProperty("osservio.fixtures"), "diagnostic-fingerprints.tsv"), StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t");
            assertEquals(fields[0], fields[1], DiagnosticFingerprint.make(evidence(fields[0])));
        }
    }
    private SystemDiagnostic evidence(String name) {
        Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> metadata = new EnumMap<>(SystemDiagnostic.MetadataKey.class);
        switch (name) {
            case "diagnostic-signal":
                metadata.put(SystemDiagnostic.MetadataKey.APP_VERSION, SystemDiagnostic.Text.capturePublic("😀"));
                metadata.put(SystemDiagnostic.MetadataKey.APP_BUILD, SystemDiagnostic.Text.capturePublic("42"));
                return SystemDiagnostic.appleSignal(AppleSignalCrash.restore("12345678-1234-1234-1234-abcdef123456", 1700000000000L,
                    AppleSignalCrash.Signal.ABORT, "#0", "18446744073709551615", 4294967295L, Arrays.asList("0", "18446744073709551615"),
                    Arrays.asList(AppleSignalCrash.Image.restore("4096", "4096", "12345678123412341234abcdef123456", "16777228", "18446744073709551615"),
                        AppleSignalCrash.Image.restore("18446744073709551615", "0", null, null, null)), metadata, true));
            case "diagnostic-android":
                return SystemDiagnostic.androidExit(9007199254740993L, 5, Integer.MIN_VALUE, Integer.MAX_VALUE, 100, Long.MAX_VALUE, null);
            case "diagnostic-crash":
                metadata.put(SystemDiagnostic.MetadataKey.APP_VERSION, SystemDiagnostic.Text.restore("测试1", true));
                metadata.put(SystemDiagnostic.MetadataKey.APP_BUILD, SystemDiagnostic.Text.capturePublic("42"));
                metadata.put(SystemDiagnostic.MetadataKey.OS_VERSION, SystemDiagnostic.Text.capturePublic("26.5"));
                metadata.put(SystemDiagnostic.MetadataKey.DEVICE_MODEL, SystemDiagnostic.Text.capturePublic("iPhone17,1"));
                return SystemDiagnostic.metricKit(Long.MIN_VALUE, 9007199254740993L, SystemDiagnostic.Kind.CRASH, metadata,
                    11, Long.MAX_VALUE, "18446744073709551615", null);
            case "diagnostic-hang":
                return SystemDiagnostic.metricKit(-1, 0, SystemDiagnostic.Kind.HANG, Collections.emptyMap(), null, null, null, 0L);
            case "delimiters-and-unicode":
                metadata.put(SystemDiagnostic.MetadataKey.APP_VERSION, SystemDiagnostic.Text.restore("a:\n汉😀\0", true));
                metadata.put(SystemDiagnostic.MetadataKey.APP_BUILD, SystemDiagnostic.Text.capturePublic("-\n"));
                return SystemDiagnostic.metricKit(Long.MIN_VALUE, Long.MAX_VALUE, SystemDiagnostic.Kind.CRASH, metadata, null, 0L, "-9223372036854775808", null);
            default: throw new IllegalArgumentException("Unknown fingerprint fixture");
        }
    }
    @Test public void receiptAccountsForEveryCandidateAndPreservesSourceLosses() {
        SystemDiagnosticBatch batch = new SystemDiagnosticBatch(SystemDiagnostic.Source.METRIC_KIT, Long.MIN_VALUE,
            Arrays.asList(evidence("diagnostic-crash"), evidence("diagnostic-hang")), 3, 4, true);
        DiagnosticRecoveryReceipt receipt = new DiagnosticRecoveryReceipt(batch, 1, 1, 0, 0, 0);
        assertEquals(SystemDiagnostic.Source.METRIC_KIT, receipt.source); assertEquals(Long.MIN_VALUE, receipt.observedAtMillis);
        assertEquals(3, receipt.sourceRejectedCount); assertEquals(4, receipt.sourceExcludedCount); assertTrue(receipt.sourceLimitReached);
        for (int count : new int[] {-1, 0, 3, 33}) assertThrows(IllegalArgumentException.class, () -> new DiagnosticRecoveryReceipt(batch, count, 0, 0, 0, 0));
    }
}
