package com.sandrox.osservio.runtime;

import com.sandrox.osservio.core.AndroidTombstone;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TombstoneDecoderTest {
    @Test public void realAndroid17FixtureRetainsOnlyFaultingThreadEvidence() throws Exception {
        byte[] data;
        try (InputStream file = Files.newInputStream(Paths.get(System.getProperty("osservio.fixtures"),
                "android-tombstone/tombstone_android17_abort.pb.gz")); GZIPInputStream gzip = new GZIPInputStream(file)) {
            data = gzip.readAllBytes();
        }
        assertEquals(14344, data.length);
        AndroidTombstone value = TombstoneDecoder.decode(data, data.length, 4733);
        assertEquals(1, value.architectureCode); assertEquals(6, value.signalNumber); assertEquals(-1, value.signalCode);
        assertEquals(4733, value.threadID); assertNull(value.faultAddress); assertEquals(4, value.frames.size());
        // Absolute PCs match the upstream regression snapshot; relative PCs/build IDs match debuggerd itself.
        assertEquals(Long.toString(0x7848f58940L), value.frames.get(0).programCounter);
        assertEquals(Long.toString(0x5b58570f94L), value.frames.get(1).programCounter);
        assertEquals(Long.toString(0x5b58570f30L), value.frames.get(2).programCounter);
        assertEquals(Long.toString(0x7848f4b954L), value.frames.get(3).programCounter);
        long[] relative = {0x7b940, 0xf94, 0xf30, 0x6e954};
        for (int i = 0; i < relative.length; i++) assertEquals(Long.toString(relative[i]), value.frames.get(i).relativeProgramCounter);
        assertEquals("2b45d17e0b10945475de75d36ca06c40", value.frames.get(0).buildID);
        assertEquals("4cd49befd0826ebecf53104e9c127181384a5dcf", value.frames.get(1).buildID);
        assertFalse(value.frameLimitReached());
        Arrays.fill(data, (byte) 0);
        assertEquals(Long.toString(0x7848f58940L), value.frames.get(0).programCounter);
    }
    @Test public void arbitraryOrderDefaultsUnsignedPrecisionAndPrivacyOmissions() throws Exception {
        byte[] rawFrame = concat(number(2, -1L), number(1, Long.MIN_VALUE), text(8, "AB01"),
            text(4, "private_function"), text(6, "/private/path"), number(3, 444));
        byte[] thread = concat(message(4, rawFrame), number(1, 99), text(2, "private thread"),
            message(3, concat(text(1, "private register"), number(2, 123))), message(5, text(4, "private memory")));
        byte[] data = concat(message(16, concat(message(2, thread), number(1, 99))),
            // A non-faulting thread contains bytes that must never be interpreted as a stack.
            message(16, concat(number(1, 100), message(2, new byte[] {0}))),
            message(10, concat(number(9, -1L), number(8, 1), number(3, -6), number(1, 6), text(2, "secret"))),
            number(6, 99), number(5, 123), text(14, "secret abort"), message(1000, new byte[500_000]));
        AndroidTombstone value = decode(data);
        assertEquals(0, value.architectureCode); assertEquals(-6, value.signalCode);
        assertEquals("18446744073709551615", value.faultAddress);
        assertEquals(1, value.frames.size()); assertEquals("18446744073709551615", value.frames.get(0).programCounter);
        assertEquals("9223372036854775808", value.frames.get(0).relativeProgramCounter);
        assertEquals("ab01", value.frames.get(0).buildID); assertEquals(4, value.textByteCount());
    }
    @Test public void absentFramesAndProto3ZeroValuesRemainHonest() throws Exception {
        AndroidTombstone missing = decode(header(number(1, 6)));
        assertEquals(99, missing.threadID); assertTrue(missing.frames.isEmpty()); assertNull(missing.faultAddress);
        AndroidTombstone zero = decode(concat(header(concat(number(1, 11), number(8, 1))),
            entry(99, concat(number(1, 99), message(4, new byte[0])))));
        assertEquals("0", zero.faultAddress); assertEquals(0, zero.signalCode);
        assertEquals("0", zero.frames.get(0).programCounter); assertEquals("0", zero.frames.get(0).relativeProgramCounter);
        assertNull(zero.frames.get(0).buildID);
    }
    @Test public void frameCapDoesNotAllocateOrParseDiscardedFrames() throws Exception {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.write(number(1, 99));
        for (int i = 0; i < 128; i++) frames.write(message(4, number(2, i)));
        frames.write(message(4, new byte[] {0}));
        AndroidTombstone value = decode(concat(header(number(1, 6)), entry(99, frames.toByteArray())));
        assertEquals(128, value.frames.size()); assertTrue(value.frameLimitReached());
        assertEquals("127", value.frames.get(127).programCounter);
    }
    @Test public void rejectsAmbiguousMismatchedAndMalformedSelectedFields() {
        byte[] header = header(number(1, 6));
        byte[][] invalid = {
            new byte[0], new byte[] {0}, number(5, 123), header(new byte[0]),
            concat(header, number(5, 124)), concat(header, number(6, 100)), concat(header, number(1, -1)),
            concat(header, number(1, 1L << 32)), concat(header, message(1, new byte[0])),
            concat(header, number(16, 1)), concat(header, entry(99, number(1, 100))),
            concat(header, message(16, number(1, 99))),
            concat(header, entry(99, number(1, 99)), entry(99, number(1, 99))),
            concat(header, message(16, concat(number(1, 99), number(1, 99), message(2, number(1, 99))))),
            header(concat(number(1, 6), number(3, 1L << 32))), header(concat(number(1, 6), number(8, 2))),
            header(concat(number(1, 6), number(9, 1))), header(concat(number(1, 6), number(1, 6))),
            withFrame(text(8, "secret")), withFrame(text(8, "abc")), withFrame(text(8, "a".repeat(130))),
            withFrame(concat(number(2, 1), number(2, 2))), withFrame(message(2, new byte[0])),
            withFrame(new byte[] {8, (byte) 128}), concat(header, new byte[] {18, 3, 1})
        };
        for (byte[] data : invalid) assertFailure(data, TombstoneDecoder.Error.INVALID);
        assertThrows(TombstoneDecoder.Failure.class, () -> TombstoneDecoder.decode(header, header.length, 999));
    }
    @Test public void rejectsResourceOverloadBeforeUnboundedWork() {
        assertFailure(new byte[TombstoneDecoder.MAXIMUM_BYTES + 1], TombstoneDecoder.Error.TOO_LARGE);
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        byte[] unknown = number(1000, 0);
        for (int i = 0; i < 32_768; i++) fields.write(unknown, 0, unknown.length);
        assertFailure(concat(header(number(1, 6)), fields.toByteArray()), TombstoneDecoder.Error.LIMIT_REACHED);
        ByteArrayOutputStream entries = new ByteArrayOutputStream();
        byte[] entry = entry(100, number(1, 100));
        for (int i = 0; i < 1025; i++) entries.write(entry, 0, entry.length);
        assertFailure(concat(header(number(1, 6)), entries.toByteArray()), TombstoneDecoder.Error.LIMIT_REACHED);
    }
    @Test public void decoderRespectsInputLengthAndRejectsRandomCorruptionWithoutRuntimeFailures() throws Exception {
        byte[] valid = header(number(1, 6));
        byte[] padded = concat(valid, new byte[] {0, 0});
        assertEquals(6, TombstoneDecoder.decode(padded, valid.length, 123).signalNumber);
        assertThrows(TombstoneDecoder.Failure.class, () -> TombstoneDecoder.decode(padded, -1, 123));
        assertThrows(TombstoneDecoder.Failure.class, () -> TombstoneDecoder.decode(null, 0, 123));
        assertThrows(TombstoneDecoder.Failure.class, () -> TombstoneDecoder.decode(padded, padded.length + 1, 123));
        Random random = new Random(169);
        for (int i = 0; i < 5000; i++) {
            byte[] bytes = new byte[random.nextInt(1024)]; random.nextBytes(bytes);
            try { decode(bytes); } catch (TombstoneDecoder.Failure expected) { assertNotNull(expected.error); }
        }
        for (int length = 0; length < valid.length; length++) {
            try { TombstoneDecoder.decode(valid, length, 123); }
            catch (TombstoneDecoder.Failure expected) { assertEquals(TombstoneDecoder.Error.INVALID, expected.error); }
        }
    }
    private static AndroidTombstone decode(byte[] data) throws TombstoneDecoder.Failure { return TombstoneDecoder.decode(data, data.length, 123); }
    private static void assertFailure(byte[] data, TombstoneDecoder.Error error) {
        assertEquals(error, assertThrows(TombstoneDecoder.Failure.class, () -> decode(data)).error);
    }
    private static byte[] header(byte[] signal) { return concat(number(5, 123), number(6, 99), message(10, signal)); }
    private static byte[] entry(int id, byte[] thread) { return message(16, concat(number(1, id), message(2, thread))); }
    private static byte[] withFrame(byte[] frame) { return concat(header(number(1, 6)), entry(99, concat(number(1, 99), message(4, frame)))); }
    private static byte[] text(int field, String value) { return message(field, value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] number(int field, long value) { return concat(varint((long) field << 3), varint(value)); }
    private static byte[] message(int field, byte[] value) { return concat(varint(((long) field << 3) | 2), varint(value.length), value); }
    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((value & ~0x7fL) != 0) { out.write((int) (value & 0x7f) | 0x80); value >>>= 7; }
        out.write((int) value); return out.toByteArray();
    }
    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }
}
