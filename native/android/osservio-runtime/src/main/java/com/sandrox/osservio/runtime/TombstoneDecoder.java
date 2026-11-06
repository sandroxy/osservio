package com.sandrox.osservio.runtime;

import static com.sandrox.osservio.runtime.internal.epitaph.WireReader.WIRETYPE_LENGTH_DELIMITED;
import static com.sandrox.osservio.runtime.internal.epitaph.WireReader.WIRETYPE_VARINT;
import static com.sandrox.osservio.runtime.internal.epitaph.WireReader.expectWireType;
import static com.sandrox.osservio.runtime.internal.epitaph.WireReader.getFieldNumber;
import static com.sandrox.osservio.runtime.internal.epitaph.WireReader.getWireType;
import com.sandrox.osservio.core.AndroidTombstone;
import com.sandrox.osservio.runtime.internal.epitaph.WireReader;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Safe-launch mapping of selected AOSP tombstone fields. Never invoked in a fatal handler. */
final class TombstoneDecoder {
    static final int MAXIMUM_BYTES = 1_048_576;
    private static final int MAXIMUM_FIELDS = 32_768;
    enum Error { INVALID, TOO_LARGE, LIMIT_REACHED }
    static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        final Error error;
        Failure(Error error) { super("Tombstone " + error.name()); this.error = error; }
    }
    private TombstoneDecoder() { }

    /** Input belongs to this call's worker and must not be mutated until return. No raw bytes escape. */
    static AndroidTombstone decode(byte[] data, int length, int expectedProcessID) throws Failure {
        if (data == null || length < 0 || length > data.length || expectedProcessID <= 0) throw new Failure(Error.INVALID);
        if (length > MAXIMUM_BYTES) throw new Failure(Error.TOO_LARGE);
        try {
            Budget budget = new Budget();
            Header header = header(new WireReader(data, 0, length), budget);
            if (header.process != expectedProcessID || header.thread <= 0 || header.signal == null)
                throw new Failure(Error.INVALID);
            List<AndroidTombstone.Frame> frames = frames(new WireReader(data, 0, length), header.thread, budget);
            return AndroidTombstone.restore(header.architecture, header.signal.number, header.signal.code, header.thread,
                header.signal.hasAddress ? unsigned(header.signal.address) : null, frames);
        } catch (Failure failure) { throw failure; }
        catch (IOException | IllegalArgumentException invalid) { throw new Failure(Error.INVALID); }
    }

    private static Header header(WireReader reader, Budget budget) throws IOException {
        Header result = new Header(); long seen = 0;
        while (reader.hasRemaining()) {
            int tag = budget.tag(reader), field = getFieldNumber(tag), wire = getWireType(tag);
            switch (field) {
                case 1: seen = once(seen, field); result.architecture = nonnegative(reader, field, wire); break;
                case 5: seen = once(seen, field); result.process = nonnegative(reader, field, wire); break;
                case 6: seen = once(seen, field); result.thread = nonnegative(reader, field, wire); break;
                case 10:
                    seen = once(seen, field); expectWireType(field, WIRETYPE_LENGTH_DELIMITED, wire);
                    result.signal = signal(reader.readMessage(), budget); break;
                default: reader.skipField(wire);
            }
        }
        return result;
    }
    private static Signal signal(WireReader reader, Budget budget) throws IOException {
        Signal result = new Signal(); long seen = 0;
        while (reader.hasRemaining()) {
            int tag = budget.tag(reader), field = getFieldNumber(tag), wire = getWireType(tag);
            switch (field) {
                case 1: seen = once(seen, field); result.number = nonnegative(reader, field, wire); break;
                case 3:
                    seen = once(seen, field); expectWireType(field, WIRETYPE_VARINT, wire);
                    long value = reader.readVarInt();
                    if (value != (int) value) throw new Failure(Error.INVALID);
                    result.code = (int) value; break;
                case 8:
                    seen = once(seen, field); expectWireType(field, WIRETYPE_VARINT, wire);
                    long present = reader.readVarInt();
                    if (present != 0 && present != 1) throw new Failure(Error.INVALID);
                    result.hasAddress = present == 1; break;
                case 9:
                    seen = once(seen, field); expectWireType(field, WIRETYPE_VARINT, wire);
                    result.address = reader.readVarInt(); break;
                default: reader.skipField(wire);
            }
        }
        if (result.number <= 0 || (!result.hasAddress && result.address != 0)) throw new Failure(Error.INVALID);
        return result;
    }
    private static List<AndroidTombstone.Frame> frames(WireReader reader, int threadID, Budget budget) throws IOException {
        List<AndroidTombstone.Frame> result = Collections.emptyList();
        boolean found = false; int entries = 0;
        // Protobuf field order is arbitrary. Learn the faulting TID before visiting its map entry.
        while (reader.hasRemaining()) {
            int tag = budget.tag(reader), field = getFieldNumber(tag), wire = getWireType(tag);
            if (field != 16) { reader.skipField(wire); continue; }
            expectWireType(field, WIRETYPE_LENGTH_DELIMITED, wire);
            if (++entries > 1024) throw new Failure(Error.LIMIT_REACHED);
            WireReader entry = reader.readMessage(), thread = null;
            int key = 0; long seen = 0;
            while (entry.hasRemaining()) {
                int entryTag = budget.tag(entry), entryField = getFieldNumber(entryTag), entryWire = getWireType(entryTag);
                if (entryField == 1) { seen = once(seen, 1); key = nonnegative(entry, entryField, entryWire); }
                else if (entryField == 2) {
                    seen = once(seen, 2); expectWireType(entryField, WIRETYPE_LENGTH_DELIMITED, entryWire);
                    thread = entry.readMessage();
                } else entry.skipField(entryWire);
            }
            if (key != threadID) continue;
            if (found || thread == null) throw new Failure(Error.INVALID);
            found = true; result = thread(thread, threadID, budget);
        }
        return result;
    }
    private static List<AndroidTombstone.Frame> thread(WireReader reader, int expectedID, Budget budget) throws IOException {
        List<AndroidTombstone.Frame> result = new ArrayList<>();
        int id = 0; long seen = 0;
        while (reader.hasRemaining()) {
            int tag = budget.tag(reader), field = getFieldNumber(tag), wire = getWireType(tag);
            if (field == 1) { seen = once(seen, field); id = nonnegative(reader, field, wire); }
            else if (field == 4) {
                expectWireType(field, WIRETYPE_LENGTH_DELIMITED, wire);
                WireReader frame = reader.readMessage();
                if (result.size() < 128) result.add(frame(frame, budget));
            } else reader.skipField(wire);
        }
        if (id != expectedID) throw new Failure(Error.INVALID);
        return result;
    }
    private static AndroidTombstone.Frame frame(WireReader reader, Budget budget) throws IOException {
        long pc = 0, relativePC = 0, seen = 0; String buildID = null;
        while (reader.hasRemaining()) {
            int tag = budget.tag(reader), field = getFieldNumber(tag), wire = getWireType(tag);
            switch (field) {
                case 1:
                    seen = once(seen, field); expectWireType(field, WIRETYPE_VARINT, wire);
                    relativePC = reader.readVarInt(); break;
                case 2:
                    seen = once(seen, field); expectWireType(field, WIRETYPE_VARINT, wire);
                    pc = reader.readVarInt(); break;
                case 8:
                    seen = once(seen, field); expectWireType(field, WIRETYPE_LENGTH_DELIMITED, wire);
                    byte[] bytes = reader.readBytes(128);
                    if (bytes.length % 2 != 0) throw new Failure(Error.INVALID);
                    for (int i = 0; i < bytes.length; i++) {
                        if (bytes[i] >= 'A' && bytes[i] <= 'F') bytes[i] += 'a' - 'A';
                        if (!((bytes[i] >= '0' && bytes[i] <= '9') || (bytes[i] >= 'a' && bytes[i] <= 'f')))
                            throw new Failure(Error.INVALID);
                    }
                    buildID = bytes.length == 0 ? null : new String(bytes, StandardCharsets.US_ASCII); break;
                default: reader.skipField(wire);
            }
        }
        return AndroidTombstone.Frame.restore(unsigned(pc), unsigned(relativePC), buildID);
    }
    private static int nonnegative(WireReader reader, int field, int wire) throws IOException {
        expectWireType(field, WIRETYPE_VARINT, wire); long value = reader.readVarInt();
        if (value < 0 || value > Integer.MAX_VALUE) throw new Failure(Error.INVALID);
        return (int) value;
    }
    private static long once(long seen, int field) throws Failure {
        long bit = 1L << field;
        if ((seen & bit) != 0) throw new Failure(Error.INVALID);
        return seen | bit;
    }
    private static String unsigned(long value) {
        return value >= 0 ? Long.toString(value) : BigInteger.valueOf(value & Long.MAX_VALUE).setBit(63).toString();
    }
    private static final class Budget {
        int count;
        int tag(WireReader reader) throws IOException {
            if (++count > MAXIMUM_FIELDS) throw new Failure(Error.LIMIT_REACHED);
            return reader.readTag();
        }
    }
    private static final class Header { int architecture, process, thread; Signal signal; }
    private static final class Signal { int number, code; boolean hasAddress; long address; }
}
