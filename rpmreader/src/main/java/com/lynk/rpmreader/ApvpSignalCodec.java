package com.lynk.rpmreader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Raw protobuf codec matching transfer_proto.SignalIdentify and Signal. */
final class ApvpSignalCodec {
    static final int ENGINE_RPM_ID = 308282774; // 0x12600596, Flyme Auto 2.0
    static final int ENGINE_RPM_ID_FLYME_AUTO_25 = 308282775; // 0x12600597
    static final String ENGINE_RPM_NAME = "EngNSafeEngN";

    static final class SignalIdentity {
        final int id;
        final String name;

        SignalIdentity(int id, String name) {
            this.id = id;
            this.name = name;
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

    static boolean isEngineRpmReading(Reading reading) {
        boolean knownId = reading.id == 0
                || reading.id == ENGINE_RPM_ID
                || reading.id == ENGINE_RPM_ID_FLYME_AUTO_25;
        boolean knownName = reading.name.isEmpty() || ENGINE_RPM_NAME.equals(reading.name);
        return knownId && knownName;
    }

    static boolean isEngineRpmReading(Reading reading, SignalIdentity expected) {
        if (expected == null || !isEngineRpmConfig(expected)) return false;
        boolean idMatches = reading.id == 0 || reading.id == expected.id;
        boolean nameMatches = reading.name.isEmpty() || expected.name.isEmpty()
                || expected.name.equals(reading.name);
        return idMatches && nameMatches;
    }

    static byte[] encodeIdentify(int id, String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, 8);
        writeVarint(out, id);
        byte[] text = name.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, 18);
        writeVarint(out, text.length);
        out.write(text, 0, text.length);
        return out.toByteArray();
    }

    static Reading decodeSignal(byte[] data) throws IOException {
        ProtoReader reader = new ProtoReader(data);
        int id = 0;
        String name = "";
        int mode = 0;
        Float firstFloat = null;
        ProtoReader.Field field;
        while ((field = reader.next()) != null) {
            if (field.number == 1 && field.wireType == 2) {
                byte[] identify = reader.readBytes();
                id = ProtoReader.firstInt32(identify, 1, 0);
                byte[] nameBytes = ProtoReader.firstBytes(identify, 2);
                if (nameBytes != null) {
                    name = new String(nameBytes, StandardCharsets.UTF_8);
                }
            } else if (field.number == 2 && field.wireType == 0) {
                mode = (int) reader.readVarint();
            } else if (field.number == 5 && field.wireType == 5) {
                if (firstFloat == null) {
                    firstFloat = Float.intBitsToFloat(reader.readFixed32());
                } else {
                    reader.readFixed32();
                }
            } else if (field.number == 5 && field.wireType == 2) {
                byte[] packed = reader.readBytes();
                if (firstFloat == null && packed.length >= 4) {
                    firstFloat = Float.intBitsToFloat((packed[0] & 0xff)
                            | ((packed[1] & 0xff) << 8)
                            | ((packed[2] & 0xff) << 16)
                            | ((packed[3] & 0xff) << 24));
                }
            } else {
                reader.skip(field);
            }
        }
        if (firstFloat == null) {
            throw new IOException("APVP response contains no float value");
        }
        return new Reading(id, name, mode, firstFloat);
    }

    static byte[] encodeInt64(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, 8);
        writeVarint(out, value);
        return out.toByteArray();
    }

    /** Decodes GetAllTransferResponse.repeated Transfer transfers = 1. */
    static List<Long> decodeTransferIds(byte[] data) throws IOException {
        List<Long> result = new ArrayList<>();
        ProtoReader reader = new ProtoReader(data);
        ProtoReader.Field field;
        while ((field = reader.next()) != null) {
            if (field.number == 1 && field.wireType == 2) {
                byte[] transfer = reader.readBytes();
                ProtoReader transferReader = new ProtoReader(transfer);
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

    /** SignalConfig field 1 is its nested SignalIdentify. */
    static boolean isSignalConfig(byte[] data, int wantedId, String wantedName) throws IOException {
        SignalIdentity identity = decodeSignalConfigIdentity(data);
        return identity != null && (identity.id == wantedId || wantedName.equals(identity.name));
    }

    static SignalIdentity decodeSignalConfigIdentity(byte[] data) throws IOException {
        byte[] identify = ProtoReader.firstBytes(data, 1);
        if (identify == null) return null;
        int id = ProtoReader.firstInt32(identify, 1, 0);
        byte[] nameBytes = ProtoReader.firstBytes(identify, 2);
        String name = nameBytes == null ? "" : new String(nameBytes, StandardCharsets.UTF_8);
        return new SignalIdentity(id, name);
    }

    static boolean isEngineRpmConfig(SignalIdentity identity) {
        if (identity == null) return false;
        if (ENGINE_RPM_NAME.equals(identity.name)) return identity.id > 0;
        return identity.name.isEmpty()
                && (identity.id == ENGINE_RPM_ID
                || identity.id == ENGINE_RPM_ID_FLYME_AUTO_25);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        do {
            int current = (int) (value & 0x7f);
            value >>>= 7;
            out.write(value == 0 ? current : current | 0x80);
        } while (value != 0);
    }
}
