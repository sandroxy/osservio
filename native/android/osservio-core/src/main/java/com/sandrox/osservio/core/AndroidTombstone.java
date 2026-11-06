package com.sandrox.osservio.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Reviewed address evidence from an Android system tombstone, without raw memory, paths or messages. */
public final class AndroidTombstone {
    public static final class Frame {
        /** Canonical unsigned 64-bit decimal strings. The relative PC is the unwinder's ELF-relative PC, not a file offset. */
        public final String programCounter, relativeProgramCounter;
        /** Lowercase ELF build ID, or null when unavailable. This is not a filename or a Mach-O UUID. */
        public final String buildID;
        private Frame(String pc, String relativePC, String buildID) {
            programCounter = pc; relativeProgramCounter = relativePC; this.buildID = buildID;
        }
        public static Frame restore(String programCounter, String relativeProgramCounter, String buildID) {
            unsigned(programCounter); unsigned(relativeProgramCounter);
            if (buildID != null && (buildID.isEmpty() || buildID.length() > 128 || buildID.length() % 2 != 0 ||
                !buildID.matches("[0-9a-f]+"))) throw new IllegalArgumentException("Invalid ELF build ID");
            return new Frame(programCounter, relativeProgramCounter, buildID);
        }
    }

    /** AOSP Architecture value: ARM32=0, ARM64=1, X86=2, X86_64=3, RISCV64=4, NONE=5. Unknown nonnegative values are preserved. */
    public final int architectureCode;
    public final int signalNumber, signalCode;
    /** OS thread ID, not an enumeration index. */
    public final int threadID;
    /** Canonical unsigned 64-bit decimal, or null when the signal has no fault address. */
    public final String faultAddress;
    /** Faulting-thread frames, newest first. Empty means unavailable; a shorter stack does not prove completeness. */
    public final List<Frame> frames;
    private AndroidTombstone(int architecture, int signal, int code, int thread, String address, List<Frame> frames) {
        architectureCode = architecture; signalNumber = signal; signalCode = code;
        threadID = thread; faultAddress = address; this.frames = frames;
    }
    /** The retained frame count reached the cap; an unknown number of frames may be absent. */
    public boolean frameLimitReached() { return frames.size() == 128; }
    public int textByteCount() {
        int total = 0;
        for (Frame frame : frames) if (frame.buildID != null) total += frame.buildID.length();
        return total;
    }
    public static AndroidTombstone restore(int architectureCode, int signalNumber, int signalCode, int threadID,
                                           String faultAddress, List<Frame> frames) {
        Objects.requireNonNull(frames, "frames");
        if (architectureCode < 0 || signalNumber <= 0 || threadID <= 0 || frames.size() > 128)
            throw new IllegalArgumentException("Invalid Android tombstone evidence");
        if (faultAddress != null) unsigned(faultAddress);
        List<Frame> copy = new ArrayList<>(frames.size());
        for (Frame frame : frames) copy.add(Objects.requireNonNull(frame, "frame"));
        return new AndroidTombstone(architectureCode, signalNumber, signalCode, threadID, faultAddress,
            Collections.unmodifiableList(copy));
    }
    private static void unsigned(String value) {
        if (value == null || value.length() > 20 || !value.matches("0|[1-9][0-9]*") ||
            new BigInteger(value).compareTo(new BigInteger("18446744073709551615")) > 0)
            throw new IllegalArgumentException("Invalid unsigned tombstone value");
    }
}
