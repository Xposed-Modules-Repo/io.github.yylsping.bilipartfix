package io.github.yylsping.bilipartfix;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ProtoWireTest {
    @Test public void preservesLongIdsAndUtf8() {
        byte[] wire = new ProtoWire.Writer().number(1, 9_876_543_210L)
                .number(2, Long.MAX_VALUE).number(3, -1).text(4, "充电试看").bytes();
        List<ProtoWire.Field> fields = ProtoWire.read(wire);
        assertEquals(9_876_543_210L, ProtoWire.number(fields, 1));
        assertEquals(Long.MAX_VALUE, ProtoWire.number(fields, 2));
        assertEquals(-1, ProtoWire.number(fields, 3));
        assertEquals("充电试看", ProtoWire.text(fields, 4));
    }

    @Test public void rejectsMalformedAndOversizedMessages() {
        byte[][] invalid = {{0}, {8, (byte) 128}, {10, 5, 1}, {15},
                {(byte) 0x88, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 1, 0},
                {8, -1, -1, -1, -1, -1, -1, -1, -1, -1, 2}, new byte[2 * 1024 * 1024 + 1]};
        for (byte[] input : invalid) {
            assertThrows(IllegalArgumentException.class, () -> ProtoWire.read(input));
        }
    }

    @Test public void skipsUnknownFixedWidthFields() {
        List<ProtoWire.Field> fields = ProtoWire.read(new byte[]{13, 1, 2, 3, 4, 16, 7});
        assertEquals(7, ProtoWire.number(fields, 2));
    }

    @Test public void translatesArcRestrictionsBySchemaRatherThanFieldPosition() {
        byte[] disabled = new ProtoWire.Writer().number(1, 1).number(2, 1).bytes();
        byte[] source = new ProtoWire.Writer()
                .message(1, new ProtoWire.Writer().number(1, 2).message(2, disabled).bytes())
                .message(1, new ProtoWire.Writer().number(1, 9).message(2, disabled).bytes())
                .message(1, new ProtoWire.Writer().number(1, 34).message(2, disabled).bytes())
                .message(1, new ProtoWire.Writer().number(1, 999).message(2, disabled).bytes()).bytes();
        List<ProtoWire.Field> legacy = ProtoWire.read(PlayerUniteFix.mapArcConf(source));
        assertEquals(3, legacy.size());
        assertArrayEquals(disabled, ProtoWire.message(legacy, 3)); // casting
        assertArrayEquals(disabled, ProtoWire.message(legacy, 1)); // background play
        assertArrayEquals(disabled, ProtoWire.message(legacy, 29)); // screen recording
    }
}
