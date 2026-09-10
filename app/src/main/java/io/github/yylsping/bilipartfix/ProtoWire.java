package io.github.yylsping.bilipartfix;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Small bounded wire adapter for the player-unite messages absent from 7.4.0. */
final class ProtoWire {
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    static final class Field {
        final int number;
        final int wire;
        final long value;
        final byte[] bytes;

        Field(int number, int wire, long value, byte[] bytes) {
            this.number = number;
            this.wire = wire;
            this.value = value;
            this.bytes = bytes;
        }
    }

    static List<Field> read(byte[] data) {
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("protobuf too large");
        List<Field> fields = new ArrayList<>();
        int[] cursor = {0};
        while (cursor[0] < data.length) {
            long tag = varint(data, cursor);
            if ((tag >>> 3) > 0x1fffffffL) throw new IllegalArgumentException("invalid protobuf tag");
            int number = (int) (tag >>> 3), wire = (int) (tag & 7);
            if (number <= 0 || number > 0x1fffffff || fields.size() >= 8192)
                throw new IllegalArgumentException("invalid protobuf field");
            if (wire == 0) {
                fields.add(new Field(number, wire, varint(data, cursor), null));
            } else {
                long length = wire == 2 ? varint(data, cursor) : wire == 1 ? 8 : wire == 5 ? 4 : -1;
                if (length < 0 || length > data.length - cursor[0])
                    throw new IllegalArgumentException("invalid protobuf length");
                int end = cursor[0] + (int) length;
                fields.add(new Field(number, wire, 0, Arrays.copyOfRange(data, cursor[0], end)));
                cursor[0] = end;
            }
        }
        return fields;
    }

    private static long varint(byte[] data, int[] cursor) {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (cursor[0] == data.length) throw new IllegalArgumentException("truncated varint");
            int value = data[cursor[0]++] & 255;
            if (shift == 63 && value > 1) throw new IllegalArgumentException("varint overflow");
            result |= (long) (value & 127) << shift;
            if ((value & 128) == 0) return result;
        }
        throw new IllegalArgumentException("varint overflow");
    }

    static byte[] message(List<Field> fields, int number) {
        for (Field field : fields) if (field.number == number && field.wire == 2) return field.bytes;
        return new byte[0];
    }

    static long number(List<Field> fields, int number) {
        for (Field field : fields) if (field.number == number && field.wire == 0) return field.value;
        return 0;
    }

    static String text(List<Field> fields, int number) {
        return new String(message(fields, number), StandardCharsets.UTF_8);
    }

    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer number(int field, long value) {
            varint((long) field << 3);
            varint(value);
            return this;
        }

        Writer message(int field, byte[] bytes) {
            varint(((long) field << 3) | 2);
            varint(bytes.length);
            out.write(bytes, 0, bytes.length);
            return this;
        }

        Writer text(int field, String text) {
            return message(field, text.getBytes(StandardCharsets.UTF_8));
        }

        private void varint(long value) {
            while ((value & ~127L) != 0) {
                out.write(((int) value & 127) | 128);
                value >>>= 7;
            }
            out.write((int) value);
        }

        byte[] bytes() { return out.toByteArray(); }
    }
}
