package io.github.zylideveloper.rpmreader;

import java.io.IOException;
/** Small protobuf reader for the exact VHAL messages used by the RPM path. */
final class ProtoReader {
    static final class Field {
        final int number;
        final int wireType;

        Field(int number, int wireType) {
            this.number = number;
            this.wireType = wireType;
        }
    }

    private final byte[] data;
    private int position;

    ProtoReader(byte[] data) {
        this.data = data;
    }

    Field next() throws IOException {
        if (position >= data.length) {
            return null;
        }
        long tag = readVarint();
        int number = (int) (tag >>> 3);
        int wireType = (int) (tag & 7);
        if (number <= 0) {
            throw new IOException("invalid protobuf tag");
        }
        return new Field(number, wireType);
    }

    long readVarint() throws IOException {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (position >= data.length) {
                throw new IOException("truncated varint");
            }
            int value = data[position++] & 0xff;
            result |= (long) (value & 0x7f) << shift;
            if ((value & 0x80) == 0) {
                return result;
            }
        }
        throw new IOException("malformed varint");
    }

    byte[] readBytes() throws IOException {
        int size = (int) readVarint();
        if (size < 0 || position + size > data.length) {
            throw new IOException("truncated bytes");
        }
        byte[] result = new byte[size];
        System.arraycopy(data, position, result, 0, size);
        position += size;
        return result;
    }

    int readFixed32() throws IOException {
        require(4);
        int result = (data[position] & 0xff)
                | ((data[position + 1] & 0xff) << 8)
                | ((data[position + 2] & 0xff) << 16)
                | ((data[position + 3] & 0xff) << 24);
        position += 4;
        return result;
    }

    void skip(Field field) throws IOException {
        switch (field.wireType) {
            case 0: readVarint(); return;
            case 1: require(8); position += 8; return;
            case 2: int size = (int) readVarint(); require(size); position += size; return;
            case 5: require(4); position += 4; return;
            default: throw new IOException("unsupported wire type " + field.wireType);
        }
    }

    private void require(int count) throws IOException {
        if (count < 0 || position + count > data.length) {
            throw new IOException("truncated protobuf field");
        }
    }

    static int firstInt32(byte[] data, int wanted, int fallback) throws IOException {
        ProtoReader reader = new ProtoReader(data);
        Field field;
        while ((field = reader.next()) != null) {
            if (field.number == wanted && field.wireType == 0) {
                return (int) reader.readVarint();
            }
            reader.skip(field);
        }
        return fallback;
    }

    static Integer firstSint32(byte[] data, int wanted) throws IOException {
        ProtoReader reader = new ProtoReader(data);
        Field field;
        while ((field = reader.next()) != null) {
            if (field.number == wanted && field.wireType == 0) {
                int encoded = (int) reader.readVarint();
                return (encoded >>> 1) ^ -(encoded & 1);
            }
            if (field.number == wanted && field.wireType == 2) {
                byte[] packed = reader.readBytes();
                if (packed.length > 0) {
                    ProtoReader packedReader = new ProtoReader(packed);
                    int encoded = (int) packedReader.readVarint();
                    return (encoded >>> 1) ^ -(encoded & 1);
                }
                return null;
            }
            reader.skip(field);
        }
        return null;
    }

    static byte[] firstBytes(byte[] data, int wanted) throws IOException {
        ProtoReader reader = new ProtoReader(data);
        Field field;
        while ((field = reader.next()) != null) {
            if (field.number == wanted && field.wireType == 2) {
                return reader.readBytes();
            }
            reader.skip(field);
        }
        return null;
    }
}
