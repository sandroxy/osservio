// Derived from Epitaph, copyright (c) 2026 Above Vacant, MIT licensed.
// See native/android/vendor/epitaph for provenance and local changes.
package com.sandrox.osservio.runtime.internal.epitaph;

import static org.junit.Assert.*;

import java.io.EOFException;
import java.io.IOException;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.Test;

@RunWith(Enclosed.class)
public final class WireReaderTest {

  // --- Varint ---

  public static class Varint {

    @Test
    public void zero() throws IOException {
      WireReader r = new WireReader(new byte[] {0x00});
      assertEquals(0, r.readVarInt());
      assertFalse(r.hasRemaining());
    }

    @Test
    public void singleByte() throws IOException {
      WireReader r = new WireReader(new byte[] {0x01});
      assertEquals(1, r.readVarInt());
    }

    @Test
    public void maxSingleByte() throws IOException {
      WireReader r = new WireReader(new byte[] {0x7F});
      assertEquals(127, r.readVarInt());
    }

    @Test
    public void twoByte() throws IOException {
      // 300 = 0b100101100 -> 0xAC 0x02
      WireReader r = new WireReader(new byte[] {(byte) 0xAC, 0x02});
      assertEquals(300, r.readVarInt());
    }

    @Test
    public void maxUint32() throws IOException {
      // 0xFFFFFFFF = 4294967295
      WireReader r =
          new WireReader(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F});
      assertEquals(0xFFFFFFFFL, r.readVarInt());
    }

    @Test
    public void maxUint64() throws IOException {
      // 0xFFFFFFFFFFFFFFFF encoded as 10 bytes of 0xFF, last byte 0x01
      WireReader r =
          new WireReader(
              new byte[] {
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                0x01
              });
      assertEquals(-1L, r.readVarInt()); // all bits set = -1 as signed long
    }

    @Test
    public void negativeOneAsSigned() throws IOException {
      // protobuf encodes -1 as 10 bytes (sign-extended varint)
      WireReader r =
          new WireReader(
              new byte[] {
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                0x01
              });
      assertEquals(-1, r.readVarInt32());
    }

    @Test
    public void truncatedVarintThrows() {
      // Continuation bit set but no more bytes
      WireReader r = new WireReader(new byte[] {(byte) 0x80});
      assertThrows(EOFException.class, r::readVarInt);
    }

    @Test
    public void truncatedMultiByteVarintThrows() {
      // Two continuation bytes, then EOF
      WireReader r = new WireReader(new byte[] {(byte) 0x80, (byte) 0x80});
      assertThrows(EOFException.class, r::readVarInt);
    }

    @Test
    public void malformedVarintTooLongThrows() {
      // 11 continuation bytes — exceeds 64-bit capacity
      WireReader r =
          new WireReader(
              new byte[] {
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                (byte) 0x80,
                0x01
              });
      IOException ex = assertThrows(IOException.class, r::readVarInt);
      assertTrue(ex.getMessage().contains("Malformed varint"));
    }

    @Test
    public void emptyBufferThrows() {
      WireReader r = new WireReader(new byte[] {});
      assertThrows(EOFException.class, r::readVarInt);
    }

    @Test
    public void readMultipleSequentially() throws IOException {
      // 1, 300, 0
      WireReader r = new WireReader(new byte[] {0x01, (byte) 0xAC, 0x02, 0x00});
      assertEquals(1, r.readVarInt());
      assertEquals(300, r.readVarInt());
      assertEquals(0, r.readVarInt());
      assertFalse(r.hasRemaining());
    }

    @Test
    public void boolTrueAndFalse() throws IOException {
      WireReader r = new WireReader(new byte[] {0x01, 0x00});
      assertTrue(r.readBool());
      assertFalse(r.readBool());
    }

    @Test
    public void boolNonOneIsTrue() throws IOException {
      WireReader r = new WireReader(new byte[] {0x42});
      assertTrue(r.readBool());
    }
  }

  // --- Fixed ---

  public static class Fixed {

    @Test
    public void fixed32LittleEndian() throws IOException {
      // 0x04030201
      WireReader r = new WireReader(new byte[] {0x01, 0x02, 0x03, 0x04});
      assertEquals(0x04030201, r.readFixed32());
    }

    @Test
    public void fixed64LittleEndian() throws IOException {
      WireReader r = new WireReader(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08});
      assertEquals(0x0807060504030201L, r.readFixed64());
    }

    @Test
    public void fixed32TruncatedThrows() {
      WireReader r = new WireReader(new byte[] {0x01, 0x02, 0x03});
      assertThrows(EOFException.class, r::readFixed32);
    }

    @Test
    public void fixed64TruncatedThrows() {
      WireReader r = new WireReader(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07});
      assertThrows(EOFException.class, r::readFixed64);
    }

    @Test
    public void fixed32AllOnes() throws IOException {
      WireReader r =
          new WireReader(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
      assertEquals(-1, r.readFixed32());
    }

    @Test
    public void fixed64AllOnes() throws IOException {
      WireReader r =
          new WireReader(
              new byte[] {
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
              });
      assertEquals(-1L, r.readFixed64());
    }
  }

  // --- Length-delimited ---

  public static class LengthDelimited {

    @Test
    public void readBytes() throws IOException {
      // length=3, then 3 bytes
      WireReader r = new WireReader(new byte[] {0x03, 0x41, 0x42, 0x43});
      assertArrayEquals(new byte[] {0x41, 0x42, 0x43}, r.readBytes());
    }

    @Test
    public void readEmptyBytes() throws IOException {
      WireReader r = new WireReader(new byte[] {0x00});
      assertArrayEquals(new byte[] {}, r.readBytes());
    }

    @Test
    public void readString() throws IOException {
      // length=5, "hello"
      WireReader r = new WireReader(new byte[] {0x05, 0x68, 0x65, 0x6C, 0x6C, 0x6F});
      assertEquals("hello", r.readString());
    }

    @Test
    public void readEmptyString() throws IOException {
      WireReader r = new WireReader(new byte[] {0x00});
      assertEquals("", r.readString());
    }

    @Test
    public void readStringUtf8() throws IOException {
      // "ä" = 0xC3 0xA4 in UTF-8
      WireReader r = new WireReader(new byte[] {0x02, (byte) 0xC3, (byte) 0xA4});
      assertEquals("ä", r.readString());
    }

    @Test
    public void truncatedLengthDelimitedThrows() {
      // Claims length=5 but only 2 bytes follow
      WireReader r = new WireReader(new byte[] {0x05, 0x41, 0x42});
      assertThrows(EOFException.class, r::readBytes);
    }

    @Test
    public void lengthTooLargeThrows() {
      // 0x80 0x80 0x80 0x80 0x08 = 2147483648, which exceeds Java's max byte[] size.
      WireReader r =
          new WireReader(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x08});
      IOException ex = assertThrows(IOException.class, r::readBytes);
      assertTrue(ex.getMessage().contains("Length too large"));
    }

    @Test
    public void readMessage() throws IOException {
      // Embedded message: length=2, containing varint 150 (0x96 0x01)
      WireReader r = new WireReader(new byte[] {0x02, (byte) 0x96, 0x01});
      WireReader sub = r.readMessage();
      assertEquals(150, sub.readVarInt());
      assertFalse(sub.hasRemaining());
      assertFalse(r.hasRemaining());
    }
  }

  // --- Tags ---

  public static class Tags {

    @Test
    public void fieldNumberAndWireType() {
      // Field 1, wire type 0 (varint) = (1 << 3) | 0 = 0x08
      assertEquals(1, WireReader.getFieldNumber(0x08));
      assertEquals(0, WireReader.getWireType(0x08));
    }

    @Test
    public void fieldNumberAndWireTypeLengthDelimited() {
      // Field 2, wire type 2 = (2 << 3) | 2 = 0x12
      assertEquals(2, WireReader.getFieldNumber(0x12));
      assertEquals(2, WireReader.getWireType(0x12));
    }

    @Test
    public void highFieldNumber() {
      // Field 100, wire type 0 = (100 << 3) | 0 = 800 = 0x320
      assertEquals(100, WireReader.getFieldNumber(0x320));
      assertEquals(0, WireReader.getWireType(0x320));
    }

    @Test
    public void readTagAtEnd() throws IOException {
      WireReader r = new WireReader(new byte[] {});
      assertEquals(0, r.readTag());
    }

    @Test
    public void readTagReturnsFullTag() throws IOException {
      // Field 1, varint = 0x08
      WireReader r = new WireReader(new byte[] {0x08, 0x00});
      int tag = r.readTag();
      assertEquals(1, WireReader.getFieldNumber(tag));
      assertEquals(0, WireReader.getWireType(tag));
    }
  }

  // --- Skip ---

  public static class Skip {

    @Test
    public void skipVarint() throws IOException {
      // varint 300 (2 bytes) then varint 1
      WireReader r = new WireReader(new byte[] {(byte) 0xAC, 0x02, 0x01});
      r.skipField(WireReader.WIRETYPE_VARINT);
      assertEquals(1, r.readVarInt());
    }

    @Test
    public void skipFixed64() throws IOException {
      WireReader r = new WireReader(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 0x42});
      r.skipField(WireReader.WIRETYPE_FIXED64);
      assertEquals(0x42, r.readVarInt());
    }

    @Test
    public void skipFixed32() throws IOException {
      WireReader r = new WireReader(new byte[] {1, 2, 3, 4, 0x42});
      r.skipField(WireReader.WIRETYPE_FIXED32);
      assertEquals(0x42, r.readVarInt());
    }

    @Test
    public void skipLengthDelimited() throws IOException {
      // length=3, 3 bytes of data, then varint 0x42
      WireReader r = new WireReader(new byte[] {0x03, 0x41, 0x42, 0x43, 0x42});
      r.skipField(WireReader.WIRETYPE_LENGTH_DELIMITED);
      assertEquals(0x42, r.readVarInt());
    }

    @Test
    public void skipUnknownWireTypeThrows() {
      WireReader r = new WireReader(new byte[] {0x00});
      IOException ex = assertThrows(IOException.class, () -> r.skipField(3));
      assertTrue(ex.getMessage().contains("Unknown wire type"));
    }

    @Test
    public void skipTruncatedFixed64Throws() {
      WireReader r = new WireReader(new byte[] {1, 2, 3});
      assertThrows(EOFException.class, () -> r.skipField(WireReader.WIRETYPE_FIXED64));
    }

    @Test
    public void skipTruncatedFixed32Throws() {
      WireReader r = new WireReader(new byte[] {1, 2});
      assertThrows(EOFException.class, () -> r.skipField(WireReader.WIRETYPE_FIXED32));
    }

    @Test
    public void skipTruncatedLengthDelimitedThrows() {
      // claims length=10, only 2 bytes
      WireReader r = new WireReader(new byte[] {0x0A, 0x01, 0x02});
      assertThrows(EOFException.class, () -> r.skipField(WireReader.WIRETYPE_LENGTH_DELIMITED));
    }
  }

  // --- Wire type validation ---

  public static class WireTypeValidation {

    @Test
    public void expectWireTypeMatchDoesNotThrow() throws IOException {
      WireReader.expectWireType(1, WireReader.WIRETYPE_VARINT, WireReader.WIRETYPE_VARINT);
    }

    @Test
    public void expectWireTypeMismatchThrows() {
      IOException ex =
          assertThrows(
              IOException.class,
              () ->
                  WireReader.expectWireType(
                      5, WireReader.WIRETYPE_VARINT, WireReader.WIRETYPE_LENGTH_DELIMITED));
      assertTrue(ex.getMessage().contains("Field 5"));
      assertTrue(ex.getMessage().contains("varint"));
      assertTrue(ex.getMessage().contains("length-delimited"));
    }
  }

  // --- Offset/limit constructor ---

  public static class OffsetLimit {

    @Test
    public void readsOnlyWithinBounds() throws IOException {
      byte[] data = new byte[] {0x00, 0x01, 0x02, 0x03, 0x00};
      WireReader r = new WireReader(data, 1, 3);
      assertEquals(3, r.remaining());
      assertEquals(1, r.readVarInt());
      assertEquals(2, r.readVarInt());
      assertEquals(3, r.readVarInt());
      assertFalse(r.hasRemaining());
    }
  }

}
