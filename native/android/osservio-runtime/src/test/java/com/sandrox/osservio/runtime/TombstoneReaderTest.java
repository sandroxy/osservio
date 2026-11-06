package com.sandrox.osservio.runtime;

import com.sandrox.osservio.core.SystemDiagnostic.TombstoneState;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TombstoneReaderTest {
    // AOSP protobuf: PID=123, TID=99, signal=6; absent stack is valid but unavailable.
    private static final byte[] VALID = {40, 123, 48, 99, 82, 2, 8, 6};
    @Test public void closesOwnedStreamAndDoesNotRetainRawInput() {
        AtomicInteger closed = new AtomicInteger();
        ByteArrayInputStream input = new ByteArrayInputStream(VALID) {
            @Override public void close() { closed.incrementAndGet(); }
        };
        TombstoneReader reader = new TombstoneReader();
        TombstoneReader.Result result = reader.read(() -> input, 123);
        assertEquals(TombstoneState.AVAILABLE, result.state); assertEquals(1, closed.get());
        assertTrue(result.evidence.frames.isEmpty()); assertEquals(6, result.evidence.signalNumber);
        assertEquals(TombstoneState.INVALID, reader.read(() -> new ByteArrayInputStream(new byte[] {0}), 123).state);
        assertEquals(99, result.evidence.threadID);
    }
    @Test public void unavailableInvalidAndMismatchedInputsHaveDifferentStates() {
        TombstoneReader reader = new TombstoneReader();
        assertEquals(TombstoneState.UNAVAILABLE, reader.read(() -> null, 123).state);
        assertEquals(TombstoneState.UNAVAILABLE, reader.read(() -> { throw new IOException("denied"); }, 123).state);
        assertEquals(TombstoneState.UNAVAILABLE, reader.read(() -> { throw new SecurityException("denied"); }, 123).state);
        assertEquals(TombstoneState.INVALID, reader.read(() -> new ByteArrayInputStream(new byte[0]), 123).state);
        assertEquals(TombstoneState.INVALID, reader.read(() -> new ByteArrayInputStream(VALID), 124).state);
    }
    @Test public void singleReportLimitConsumesOnlyOneExtraByteAndCloses() {
        AtomicInteger read = new AtomicInteger(), closed = new AtomicInteger();
        InputStream input = new InputStream() {
            @Override public int read() { read.incrementAndGet(); return 0; }
            @Override public int read(byte[] bytes, int offset, int length) {
                read.addAndGet(length); return length;
            }
            @Override public void close() { closed.incrementAndGet(); }
        };
        TombstoneReader.Result result = new TombstoneReader().read(() -> input, 123);
        assertEquals(TombstoneState.TOO_LARGE, result.state); assertNull(result.evidence);
        assertEquals(TombstoneDecoder.MAXIMUM_BYTES + 1, read.get()); assertEquals(1, closed.get());
    }
    @Test public void batchBudgetCountsAllStreamsAndDoesNotOpenAfterExhaustion() {
        TombstoneReader reader = new TombstoneReader(VALID.length * 2 + 1);
        assertEquals(TombstoneState.AVAILABLE, reader.read(() -> new ByteArrayInputStream(VALID), 123).state);
        assertEquals(TombstoneState.AVAILABLE, reader.read(() -> new ByteArrayInputStream(VALID), 123).state);
        assertEquals(TombstoneState.LIMIT_REACHED, reader.read(() -> new ByteArrayInputStream(VALID), 123).state);
        AtomicInteger opened = new AtomicInteger();
        assertEquals(TombstoneState.LIMIT_REACHED, reader.read(() -> { opened.incrementAndGet(); return null; }, 123).state);
        assertEquals(0, opened.get());
        // Reading exactly the last admitted byte does not prove that EOF followed it.
        assertEquals(TombstoneState.LIMIT_REACHED,
            new TombstoneReader(VALID.length).read(() -> new ByteArrayInputStream(VALID), 123).state);
    }
    @Test public void thrownReadKeepsItsReservationAndCloses() {
        AtomicInteger closed = new AtomicInteger();
        InputStream input = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("read failed"); }
            @Override public void close() { closed.incrementAndGet(); }
        };
        TombstoneReader reader = new TombstoneReader(100);
        assertEquals(TombstoneState.UNAVAILABLE, reader.read(() -> input, 123).state);
        assertEquals(1, closed.get());
        assertEquals(TombstoneState.LIMIT_REACHED, reader.read(() -> { fail("must not reopen"); return null; }, 123).state);
    }
    @Test public void zeroLengthReadCannotSpinAndCloseFailureRemainsVisible() {
        InputStream zeros = new ByteArrayInputStream(VALID) {
            @Override public int read(byte[] bytes, int offset, int length) { return 0; }
        };
        assertEquals(TombstoneState.AVAILABLE, new TombstoneReader().read(() -> zeros, 123).state);
        InputStream badClose = new ByteArrayInputStream(VALID) {
            @Override public void close() throws IOException { throw new IOException("close failed"); }
        };
        assertEquals(TombstoneState.UNAVAILABLE, new TombstoneReader().read(() -> badClose, 123).state);
    }
}
