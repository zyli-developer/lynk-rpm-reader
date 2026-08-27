package com.lynk.rpmreader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Raw protobuf codec for the APVP transfer service messages used by the RPM path. */
final class ApvpSignalCodec {
    static final String ENGINE_RPM_NAME = "EngNSafeEngN";
    static final int VALUE_TYPE_INT32 = 0x00400000;
    static final int VALUE_TYPE_FLOAT = 0x00600000;

    static final class SignalConfig {
        final int id;
        final String name;
        final String module;
        final boolean readOnly;
        final int valueType;
        final int notifyMode;
        final String description;

        SignalConfig(int id, String name, String module, boolean readOnly,
                int valueType, int notifyMode, String description) {
            this.id = id;
            this.name = name;
            this.module = module;
            this.readOnly = readOnly;
            this.valueType = valueType;
            this.notifyMode = notifyMode;
            this.description = description;
        }
    }

    static final class Reading {
        final int id;
        final String name;
        final int mode;
        final float value;

        Reading(int id, String name, int mode, float value) {
            this.id = id;
            this.name = name;
            this.mode = mode;
            this.value = value;
        }
    }

    private ApvpSignalCodec() {}

    static boolean isEngineRpmConfig(SignalConfig config) {
        return config.id != 0
                && ENGINE_RPM_NAME.equals(config.name)
                && config.readOnly
                && (config.valueType == VALUE_TYPE_FLOAT
                    || config.valueType == VALUE_TYPE_INT32);
    }

    static boolean matches(Reading reading, SignalConfig config) {
        boolean hasIdentity = reading.id != 0 || !reading.name.isEmpty();
        boolean idMatches = reading.id == 0 || reading.id == config.id;
        boolean nameMatches = reading.name.isEmpty() || reading.name.equals(config.name);
        return hasIdentity && idMatches && nameMatches;
    }

    static byte[] encodeIdentify(int id, String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarintField(out, 1, id);
        writeBytesField(out, 2, name.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** ListenerRequest.mode defaults to selective (0); field 2 contains requested identities. */
    static byte[] encodeListenerRequest(int id, String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBytesField(out, 2, encodeIdentify(id, name));
        return out.toByteArray();
    }

    static Reading decodeSignal(byte[] data) throws IOException {
        ProtoReader reader = new ProtoReader(data);
        int id = 0;
        String name = "";
        int mode = 0;
        Float firstNumber = null;
        ProtoReader.Field field;
        while ((field = reader.next()) != null) {
            if (field.number == 1 && field.wireType == 2) {
                byte[] identify = reader.readBytes();
                id = ProtoReader.firstInt32(identify, 1, 0);
                byte[] nameBytes = ProtoReader.firstBytes(identify, 2);
                if (nameBytes != null) name = new String(nameBytes, StandardCharsets.UTF_8);
            } else if (field.number == 2 && field.wireType == 0) {
                mode = (int) reader.readVarint();
            } else if (field.number == 3 && field.wireType == 0) {
                int encoded = (int) reader.readVarint();
                if (firstNumber == null) firstNumber = (float) decodeZigZag32(encoded);
            } else if (field.number == 3 && field.wireType == 2) {
                byte[] packed = reader.readBytes();
                if (firstNumber == null && packed.length > 0) {
                    firstNumber = (float) decodeZigZag32((int) new ProtoReader(packed).readVarint());
                }
            } else if (field.number == 5 && field.wireType == 5) {
                int bits = reader.readFixed32();
                if (firstNumber == null) firstNumber = Float.intBitsToFloat(bits);
            } else if (field.number == 5 && field.wireType == 2) {
                byte[] packed = reader.readBytes();
                if (firstNumber == null && packed.length >= 4) {
                    firstNumber = Float.intBitsToFloat((packed[0] & 0xff)
                            | ((packed[1] & 0xff) << 8)
                            | ((packed[2] & 0xff) << 16)
                            | ((packed[3] & 0xff) << 24));
                }
            } else {
                reader.skip(field);
            }
        }
        if (firstNumber == null) throw new IOException("APVP response contains no numeric value");
        return new Reading(id, name, mode, firstNumber);
    }

    /** Decodes ListSignals.groups(field 1) -> Signals.signals(field 3). */
    static List<Reading> decodeSignalBatch(byte[] data) throws IOException {
        List<Reading> result = new ArrayList<>();
        ProtoReader outer = new ProtoReader(data);
        ProtoReader.Field outerField;
        while ((outerField = outer.next()) != null) {
            if (outerField.number != 1 || outerField.wireType != 2) {
                outer.skip(outerField);
                continue;
            }
            ProtoReader group = new ProtoReader(outer.readBytes());
            ProtoReader.Field groupField;
            while ((groupField = group.next()) != null) {
                if (groupField.number == 3 && groupField.wireType == 2) {
                    result.add(decodeSignal(group.readBytes()));
                } else {
                    group.skip(groupField);
                }
            }
        }
        return result;
    }

    static SignalConfig decodeSignalConfig(byte[] data) throws IOException {
        int id = 0;
        String name = "";
        String module = "";
        boolean readOnly = false;
        int valueType = 0;
        int notifyMode = 0;
        String description = "";
        ProtoReader reader = new ProtoReader(data);
        ProtoReader.Field field;
        while ((field = reader.next()) != null) {
            if (field.number == 1 && field.wireType == 2) {
                byte[] identify = reader.readBytes();
                id = ProtoReader.firstInt32(identify, 1, 0);
                byte[] nameBytes = ProtoReader.firstBytes(identify, 2);
                if (nameBytes != null) name = new String(nameBytes, StandardCharsets.UTF_8);
            } else if (field.number == 2 && field.wireType == 2) {
                module = new String(reader.readBytes(), StandardCharsets.UTF_8);
            } else if (field.number == 3 && field.wireType == 0) {
                readOnly = reader.readVarint() != 0;
            } else if (field.number == 4 && field.wireType == 0) {
                valueType = (int) reader.readVarint();
            } else if (field.number == 5 && field.wireType == 0) {
                notifyMode = (int) reader.readVarint();
            } else if (field.number == 8 && field.wireType == 2) {
                description = new String(reader.readBytes(), StandardCharsets.UTF_8);
            } else {
                reader.skip(field);
            }
        }
        return new SignalConfig(id, name, module, readOnly, valueType, notifyMode, description);
    }

    static byte[] encodeInt64(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarintField(out, 1, value);
        return out.toByteArray();
    }

    /** Decodes GetAllTransferResponse.repeated Transfer transfers = 1. */
    static List<Long> decodeTransferIds(byte[] data) throws IOException {
        List<Long> result = new ArrayList<>();
        ProtoReader reader = new ProtoReader(data);
        ProtoReader.Field field;
        while ((field = reader.next()) != null) {
            if (field.number == 1 && field.wireType == 2) {
                ProtoReader transferReader = new ProtoReader(reader.readBytes());
                ProtoReader.Field transferField;
                while ((transferField = transferReader.next()) != null) {
                    if (transferField.number == 1 && transferField.wireType == 0) {
                        result.add(transferReader.readVarint());
                        break;
                    }
                    transferReader.skip(transferField);
                }
            } else {
                reader.skip(field);
            }
        }
        return result;
    }

    private static int decodeZigZag32(int encoded) {
        return (encoded >>> 1) ^ -(encoded & 1);
    }

    private static void writeVarintField(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, field << 3);
        writeVarint(out, value);
    }

    private static void writeBytesField(ByteArrayOutputStream out, int field, byte[] value) {
        writeVarint(out, (field << 3) | 2);
        writeVarint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        do {
            int current = (int) (value & 0x7f);
            value >>>= 7;
            out.write(value == 0 ? current : current | 0x80);
        } while (value != 0);
    }
}
