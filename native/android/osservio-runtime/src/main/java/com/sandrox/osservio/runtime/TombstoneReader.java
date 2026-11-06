package com.sandrox.osservio.runtime;

import com.sandrox.osservio.core.AndroidTombstone;
import com.sandrox.osservio.core.SystemDiagnostic.TombstoneState;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** One system-history query's stream budget and reusable raw buffer. Confined to its worker. */
final class TombstoneReader {
    interface Source { InputStream open() throws IOException; }
    static final class Result {
        final TombstoneState state;
        final AndroidTombstone evidence;
        Result(TombstoneState state, AndroidTombstone evidence) { this.state = state; this.evidence = evidence; }
    }
    private int remaining = 8_388_608;
    private byte[] buffer;
    TombstoneReader() { }
    TombstoneReader(int budget) {
        if (budget < 0 || budget > remaining) throw new IllegalArgumentException("Invalid tombstone read budget");
        remaining = budget;
    }
    Result read(Source source, int expectedProcessID) {
        if (remaining == 0) return absent(TombstoneState.LIMIT_REACHED);
        try (InputStream stream = source.open()) {
            if (stream == null) return absent(TombstoneState.UNAVAILABLE);
            if (buffer == null) buffer = new byte[TombstoneDecoder.MAXIMUM_BYTES + 1];
            int length = 0;
            while (true) {
                int admitted = Math.min(buffer.length - length, remaining);
                if (admitted == 0) return absent(TombstoneState.LIMIT_REACHED);
                // A throwing stream may have consumed part of its request. Keep that reservation on failure.
                remaining -= admitted;
                int read = stream.read(buffer, length, admitted);
                if (read == 0) {
                    int value = stream.read();
                    if (value >= 0 && value <= 255) { buffer[length] = (byte) value; read = 1; }
                    else if (value == -1) read = -1;
                    else return absent(TombstoneState.UNAVAILABLE);
                }
                if (read == -1) {
                    remaining += admitted;
                    return new Result(TombstoneState.AVAILABLE, TombstoneDecoder.decode(buffer, length, expectedProcessID));
                }
                if (read < 0 || read > admitted) return absent(TombstoneState.UNAVAILABLE);
                length += read; remaining += admitted - read;
                if (length > TombstoneDecoder.MAXIMUM_BYTES) return absent(TombstoneState.TOO_LARGE);
            }
        } catch (TombstoneDecoder.Failure failure) {
            switch (failure.error) {
                case TOO_LARGE: return absent(TombstoneState.TOO_LARGE);
                case LIMIT_REACHED: return absent(TombstoneState.LIMIT_REACHED);
                default: return absent(TombstoneState.INVALID);
            }
        } catch (IOException | RuntimeException failure) { return absent(TombstoneState.UNAVAILABLE); }
        finally { if (buffer != null) Arrays.fill(buffer, (byte) 0); }
    }
    private static Result absent(TombstoneState state) { return new Result(state, null); }
}
