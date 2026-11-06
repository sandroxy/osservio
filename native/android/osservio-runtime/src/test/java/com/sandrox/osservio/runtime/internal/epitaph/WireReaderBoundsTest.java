package com.sandrox.osservio.runtime.internal.epitaph;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

/** Osservio-specific bounds in addition to the retained upstream regression suites. */
public final class WireReaderBoundsTest {
    @Test public void fieldLimitRejectsBeforeCopying() throws Exception {
        WireReader reader = new WireReader(new byte[] {3, 1, 2, 3});
        assertThrows(IOException.class, () -> reader.readBytes(2));
        assertArrayEquals(new byte[] {1, 2, 3}, new WireReader(new byte[] {3, 1, 2, 3}).readBytes(3));
        assertArrayEquals(new byte[0], new WireReader(new byte[] {0}).readBytes(0));
        assertThrows(IllegalArgumentException.class, () -> new WireReader(new byte[] {0}).readBytes(-1));
    }
    @Test public void submessageSharesInputButCannotEscapeItsSlice() throws Exception {
        byte[] input = {2, 8, 1, 42};
        WireReader parent = new WireReader(input), child = parent.readMessage();
        input[2] = 2;
        assertEquals(8, child.readTag()); assertEquals(2, child.readVarInt());
        assertFalse(child.hasRemaining()); assertThrows(IOException.class, child::readVarInt);
        assertEquals(42, parent.readVarInt());
    }
}
