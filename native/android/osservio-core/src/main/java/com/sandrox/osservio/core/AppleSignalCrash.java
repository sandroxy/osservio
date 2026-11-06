package com.sandrox.osservio.core;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Address-only Apple BSD fatal-signal evidence. This model does not install a recorder on Android. */
public final class AppleSignalCrash {
    public enum Signal {
        ABORT("SIGABRT"), BUS("SIGBUS"), FLOATING_POINT("SIGFPE"), ILLEGAL_INSTRUCTION("SIGILL"),
        SEGMENTATION_VIOLATION("SIGSEGV"), TRAP("SIGTRAP");
        public final String wireName;
        Signal(String name) { wireName = name; }
    }
    public static final class Image {
        /** Canonical unsigned 64-bit decimal strings. A zero size means no usable extent. */
        public final String baseAddress, size;
        /** Lowercase 32-digit Mach-O UUID without separators, or null when unavailable. */
        public final String uuid;
        /** Canonical unsigned 64-bit Mach CPU identifiers; both null when unavailable. */
        public final String cpuType, cpuSubtype;
        private Image(String base, String size, String uuid, String cpuType, String cpuSubtype) {
            baseAddress = base; this.size = size; this.uuid = uuid; this.cpuType = cpuType; this.cpuSubtype = cpuSubtype;
        }
        public static Image restore(String baseAddress, String size, String uuid, String cpuType, String cpuSubtype) {
            BigInteger base = unsigned(baseAddress), length = unsigned(size);
            if (base.add(length).compareTo(MAX_UNSIGNED) > 0 || (cpuType == null) != (cpuSubtype == null) ||
                (uuid != null && (uuid.length() != 32 || !uuid.matches("[0-9a-f]{32}")))) {
                throw new IllegalArgumentException("Invalid crash image");
            }
            if (cpuType != null) { unsigned(cpuType); unsigned(cpuSubtype); }
            return new Image(baseAddress, size, uuid, cpuType, cpuSubtype);
        }
    }
    private static final BigInteger MAX_UNSIGNED = new BigInteger("18446744073709551615");
    public final String reportID;
    /** Original recorder time at whole-second precision, separate from incident recovery time. */
    public final long timestampMillis;
    public final Signal signal;
    /** Reviewed symbolic code, or '#' followed by canonical signed 32-bit decimal. */
    public final String code;
    public final String faultAddress;
    /** Unsigned 32-bit recorder enumeration index, not an OS thread ID. Null means unavailable. */
    public final Long threadNumber;
    /** Unsigned 64-bit instruction addresses, newest first. Empty means unavailable. */
    public final List<String> frames;
    public final List<Image> images;
    public final Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> metadata;
    public final boolean imageCacheIncomplete;

    private AppleSignalCrash(String id, long time, Signal signal, String code, String address, Long thread,
                             List<String> frames, List<Image> images,
                             Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> metadata, boolean incomplete) {
        reportID = id; timestampMillis = time; this.signal = signal; this.code = code; faultAddress = address;
        threadNumber = thread; this.frames = frames; this.images = images; this.metadata = metadata; imageCacheIncomplete = incomplete;
    }
    public boolean frameLimitReached() { return frames.size() == 128; }
    public boolean imageLimitReached() { return images.size() == 512; }
    public int textByteCount() {
        int total = reportID.length() + signal.wireName.length() + code.length();
        for (Image image : images) if (image.uuid != null) total += image.uuid.length();
        for (SystemDiagnostic.Text text : metadata.values()) total += text.value.getBytes(StandardCharsets.UTF_8).length;
        return total;
    }
    public static AppleSignalCrash restore(String id, long timestampMillis, Signal signal, String code, String faultAddress,
                                          Long threadNumber, List<String> frames, List<Image> images,
                                          Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> metadata, boolean incomplete) {
        Objects.requireNonNull(id, "report ID"); Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(frames, "frames"); Objects.requireNonNull(images, "images"); Objects.requireNonNull(metadata, "metadata");
        if (id.length() != 36 || !UUID.fromString(id).toString().equals(id) || timestampMillis <= 0 || timestampMillis % 1000 != 0 ||
            !validCode(code, signal) || frames.size() > 128 || images.size() > 512 ||
            (threadNumber != null && (threadNumber < 0 || threadNumber > 4_294_967_295L)) ||
            (threadNumber == null && !frames.isEmpty()) || metadata.size() > SystemDiagnostic.MetadataKey.values().length) {
            throw new IllegalArgumentException("Invalid Apple signal crash evidence");
        }
        unsigned(faultAddress);
        List<String> copiedFrames = new ArrayList<>();
        for (String frame : frames) { unsigned(frame); copiedFrames.add(frame); }
        List<Image> copiedImages = new ArrayList<>();
        for (Image image : images) copiedImages.add(Objects.requireNonNull(image, "image"));
        Map<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> copiedMetadata = new EnumMap<>(SystemDiagnostic.MetadataKey.class);
        for (Map.Entry<SystemDiagnostic.MetadataKey, SystemDiagnostic.Text> entry : metadata.entrySet()) {
            SystemDiagnostic.Text text = Objects.requireNonNull(entry.getValue(), "metadata value");
            if (text.isTruncated || text.value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid crash metadata");
            copiedMetadata.put(Objects.requireNonNull(entry.getKey(), "metadata key"), text);
        }
        return new AppleSignalCrash(id, timestampMillis, signal, code, faultAddress, threadNumber,
            Collections.unmodifiableList(copiedFrames), Collections.unmodifiableList(copiedImages), Collections.unmodifiableMap(copiedMetadata), incomplete);
    }
    private static BigInteger unsigned(String value) {
        if (value == null || value.length() > 20 || !value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Invalid unsigned crash value");
        BigInteger result = new BigInteger(value);
        if (result.compareTo(MAX_UNSIGNED) > 0) throw new IllegalArgumentException("Overflowing crash value");
        return result;
    }
    private static boolean validCode(String code, Signal signal) {
        if (code == null || code.isEmpty() || code.length() > 16) return false;
        if (code.charAt(0) == '#') {
            try { return ("#" + Integer.parseInt(code.substring(1))).equals(code); }
            catch (NumberFormatException ignored) { return false; }
        }
        switch (signal) {
            case BUS: return Arrays.asList("BUS_NOOP", "BUS_ADRALN", "BUS_ADRERR", "BUS_OBJERR").contains(code);
            case FLOATING_POINT:
                return Arrays.asList("FPE_NOOP", "FPE_FLTDIV", "FPE_FLTOVF", "FPE_FLTUND", "FPE_FLTRES", "FPE_FLTINV", "FPE_FLTSUB", "FPE_INTDIV", "FPE_INTOVF").contains(code);
            case ILLEGAL_INSTRUCTION:
                return Arrays.asList("ILL_NOOP", "ILL_ILLOPC", "ILL_ILLTRP", "ILL_PRVOPC", "ILL_ILLOPN", "ILL_ILLADR", "ILL_PRVREG", "ILL_COPROC", "ILL_BADSTK").contains(code);
            case SEGMENTATION_VIOLATION: return Arrays.asList("SEGV_NOOP", "SEGV_MAPERR", "SEGV_ACCERR").contains(code);
            case TRAP: return Arrays.asList("TRAP_BRKPT", "TRAP_TRACE").contains(code);
            default: return false;
        }
    }
}
