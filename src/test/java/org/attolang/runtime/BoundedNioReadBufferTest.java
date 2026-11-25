package org.attolang.runtime;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BoundedNioReadBufferTest {
    @Test void zeroReadDoesNotExposePreviousOrUnwrittenBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.put(new byte[]{11, 22, 33});
        buffer.clear();
        assertFalse(BoundedNioReactor.finishRead(buffer, 0));
        assertEquals(0, buffer.remaining());
        buffer.clear();
        buffer.put((byte) 7);
        assertTrue(BoundedNioReactor.finishRead(buffer, 1));
        assertEquals(1, buffer.remaining());
        assertEquals(7, buffer.get());
        assertFalse(buffer.hasRemaining());
    }
}
