package com.lynk.rpmreader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/** Zero-dependency tests for the real APVP RPM protocol and fallback value conversion. */
public final class RpmProtoDecoderTest {
    private int passed;

    public static void main(String[] args) throws Exception {
        new RpmProtoDecoderTest().run();
    }

    private void run() throws Exception {
        convertsCarApiFloatRpm();
        rejectsInvalidCarApiRpm();
        encodesApvpSignalIdentity();
        encodesApvpListenerRequest();
        decodesApvpUnpackedFloat();
        decodesApvpPackedFloat();
        decodesApvpInt32();
        rejectsApvpResponseWithoutNumber();
        decodesDynamicApvpConfig();
        rejectsUnsafeApvpConfig();
        matchesResolvedApvpIdentity();
        decodesApvpSignalBatch();
        decodesApvpTransferIds();
        mapsGaugeMovement();
        clampsGaugeRange();
        usesConfiguredPowerPeakScale();
        mapsEngineCharacteristicZones();
        formatsAnimatedGaugeReadouts();
        formatsInstrumentReadout();
        keepsInstrumentLocationExclusive();
        validatesStartupAnimationTimeline();
        System.out.println("RPM logic tests passed: " + passed + "/21");
    }

    private void convertsCarApiFloatRpm() {
        check(CarRpmValue.toDisplayRpm(1234.4f) == 1234, "Float RPM rounds down");
        check(CarRpmValue.toDisplayRpm(1234.6f) == 1235, "Float RPM rounds up");
        check(CarRpmValue.toDisplayRpm(-1.0f) == 0, "negative RPM is clamped");
        pass();
    }

    private void rejectsInvalidCarApiRpm() {
        try {
            CarRpmValue.toDisplayRpm(Float.NaN);
            throw new AssertionError("NaN RPM must fail");
        } catch (IllegalArgumentException expected) { pass(); }
    }

    private void encodesApvpSignalIdentity() throws Exception {
        byte[] request = ApvpSignalCodec.encodeIdentify(308282999, "EngNSafeEngN");
        check(ProtoReader.firstInt32(request, 1, 0) == 308282999, "request signal id");
        check("EngNSafeEngN".equals(new String(ProtoReader.firstBytes(request, 2), "UTF-8")),
                "request signal name");
        pass();
    }

    private void encodesApvpListenerRequest() throws Exception {
        byte[] request = ApvpSignalCodec.encodeListenerRequest(308282999, "EngNSafeEngN");
        check(ProtoReader.firstInt32(request, 1, 0) == 0, "selective listener mode");
        byte[] identify = ProtoReader.firstBytes(request, 2);
        check(identify != null && ProtoReader.firstInt32(identify, 1, 0) == 308282999,
                "listener signal id");
        check("EngNSafeEngN".equals(new String(ProtoReader.firstBytes(identify, 2), "UTF-8")),
                "listener signal name");
        pass();
    }

    private void decodesApvpUnpackedFloat() throws Exception {
        byte[] identify = ApvpSignalCodec.encodeIdentify(308282999, "EngNSafeEngN");
        ApvpSignalCodec.Reading reading = ApvpSignalCodec.decodeSignal(concat(
                bytesField(1, identify), varintField(2, 1),
                fixed32Field(5, Float.floatToIntBits(1666.5f))));
        check(reading.id == 308282999 && "EngNSafeEngN".equals(reading.name), "response identity");
        check(reading.value == 1666.5f && reading.mode == 1, "unpacked Float and mode");
        pass();
    }

    private void decodesApvpPackedFloat() throws Exception {
        ApvpSignalCodec.Reading reading = ApvpSignalCodec.decodeSignal(
                bytesField(5, fixed32(Float.floatToIntBits(875.25f))));
        check(reading.value == 875.25f, "packed Float");
        pass();
    }

    private void decodesApvpInt32() throws Exception {
        ApvpSignalCodec.Reading reading = ApvpSignalCodec.decodeSignal(
                bytesField(3, varint(2400)));
        check(reading.value == 1200f, "packed sint32");
        pass();
    }

    private void rejectsApvpResponseWithoutNumber() {
        try {
            ApvpSignalCodec.decodeSignal(varintField(2, 1));
            throw new AssertionError("response without numeric value must fail");
        } catch (IOException expected) { pass(); }
    }

    private void decodesDynamicApvpConfig() throws Exception {
        byte[] configBytes = concat(
                bytesField(1, ApvpSignalCodec.encodeIdentify(308282999, "EngNSafeEngN")),
                bytesField(2, "VDDM".getBytes("UTF-8")),
                varintField(3, 1),
                varintField(4, ApvpSignalCodec.VALUE_TYPE_FLOAT),
                varintField(5, 2),
                bytesField(8, "engine speed".getBytes("UTF-8")));
        ApvpSignalCodec.SignalConfig config = ApvpSignalCodec.decodeSignalConfig(configBytes);
        check(config.id == 308282999 && "EngNSafeEngN".equals(config.name),
                "dynamic config identity");
        check("VDDM".equals(config.module) && config.readOnly, "dynamic config metadata");
        check(config.valueType == ApvpSignalCodec.VALUE_TYPE_FLOAT && config.notifyMode == 2,
                "dynamic config type and notify mode");
        check(ApvpSignalCodec.isEngineRpmConfig(config), "dynamic RPM config accepted");
        pass();
    }

    private void rejectsUnsafeApvpConfig() {
        check(!ApvpSignalCodec.isEngineRpmConfig(new ApvpSignalCodec.SignalConfig(
                        308282999, "EngNSafeEngN", "VDDM", false,
                        ApvpSignalCodec.VALUE_TYPE_FLOAT, 0, "")),
                "writable config is rejected");
        check(!ApvpSignalCodec.isEngineRpmConfig(new ApvpSignalCodec.SignalConfig(
                        308282999, "VehicleSpeed", "VDDM", true,
                        ApvpSignalCodec.VALUE_TYPE_FLOAT, 0, "")),
                "wrong signal name is rejected");
        pass();
    }

    private void matchesResolvedApvpIdentity() {
        ApvpSignalCodec.SignalConfig config = new ApvpSignalCodec.SignalConfig(
                308282999, "EngNSafeEngN", "VDDM", true,
                ApvpSignalCodec.VALUE_TYPE_FLOAT, 0, "");
        check(ApvpSignalCodec.matches(new ApvpSignalCodec.Reading(
                        308282999, "EngNSafeEngN", 0, 1200f), config),
                "runtime-discovered identity matches");
        check(!ApvpSignalCodec.matches(new ApvpSignalCodec.Reading(
                        308282998, "EngNSafeEngN", 0, 1200f), config),
                "stale numeric ID is rejected");
        check(!ApvpSignalCodec.matches(new ApvpSignalCodec.Reading(
                        0, "", 0, 1200f), config), "missing identity is rejected");
        pass();
    }

    private void decodesApvpSignalBatch() throws Exception {
        byte[] unrelated = concat(
                bytesField(1, ApvpSignalCodec.encodeIdentify(7, "VehicleSpeed")),
                fixed32Field(5, Float.floatToIntBits(55f)));
        byte[] rpm = concat(
                bytesField(1, ApvpSignalCodec.encodeIdentify(308282999, "EngNSafeEngN")),
                varintField(2, 1), fixed32Field(5, Float.floatToIntBits(1425.5f)));
        byte[] group = concat(varintField(1, 1), varintField(2, 1),
                bytesField(3, unrelated), bytesField(3, rpm));
        List<ApvpSignalCodec.Reading> readings = ApvpSignalCodec.decodeSignalBatch(
                bytesField(1, group));
        check(readings.size() == 2, "stream batch signal count");
        check(readings.get(1).id == 308282999 && readings.get(1).value == 1425.5f,
                "stream batch RPM payload");
        pass();
    }

    private void decodesApvpTransferIds() throws Exception {
        byte[] first = concat(varintField(1, 41), bytesField(2, "first".getBytes("UTF-8")));
        List<Long> ids = ApvpSignalCodec.decodeTransferIds(concat(
                bytesField(1, first), bytesField(1, varintField(1, 99))));
        check(ids.size() == 2 && ids.get(0) == 41L && ids.get(1) == 99L, "transfer IDs");
        pass();
    }

    private void mapsGaugeMovement() {
        float idle = RpmGaugeModel.angleForRpm(1300);
        float raised = RpmGaugeModel.angleForRpm(3000);
        check(raised > idle, "needle angle must advance when RPM rises");
        check(RpmGaugeModel.sweepForRpm(4000) == 120f, "4000 RPM must be gauge midpoint");
        pass();
    }

    private void clampsGaugeRange() {
        check(RpmGaugeModel.clamp(-5) == 0, "gauge lower clamp");
        check(RpmGaugeModel.clamp(9000) == 8000, "gauge upper clamp");
        pass();
    }

    private void usesConfiguredPowerPeakScale() {
        check(RpmGaugeModel.MAX_RPM == 8000, "requested display scale");
        check(RpmGaugeModel.POWER_PEAK_RPM == 5500, "BHE15-BFZ maximum-power RPM");
        check(RpmGaugeModel.angleForRpm(8000) == 390f, "full-scale needle angle");
        pass();
    }

    private void mapsEngineCharacteristicZones() {
        check(RpmGaugeModel.zoneForRpm(1200) == RpmGaugeModel.Zone.LOW, "low-speed zone");
        check(RpmGaugeModel.zoneForRpm(3000) == RpmGaugeModel.Zone.TORQUE, "peak-torque zone");
        check(RpmGaugeModel.zoneForRpm(5000) == RpmGaugeModel.Zone.POWER, "high-power zone");
        check(RpmGaugeModel.zoneForRpm(6000) == RpmGaugeModel.Zone.ABOVE_PUBLISHED_PEAK,
                "above published peak zone");
        pass();
    }

    private void formatsAnimatedGaugeReadouts() {
        check("1.3".equals(RpmGaugeModel.formatThousands(1298f)), "center x1000 readout");
        check("1298 RPM".equals(RpmGaugeModel.formatExact(1298f)), "upper-right exact readout");
        check("8.0".equals(RpmGaugeModel.formatThousands(9000f)), "formatted value clamps");
        pass();
    }

    private void formatsInstrumentReadout() {
        check("1.0 × 1000 RPM".equals(RpmDisplayText.available(1000)),
                "instrument readout uses one decimal and x1000 unit");
        check("0.9 × 1000 RPM".equals(RpmDisplayText.available(850)),
                "instrument readout rounds to one decimal");
        check("—.- × 1000 RPM".equals(RpmDisplayText.unavailable()),
                "instrument unavailable placeholder");
        pass();
    }

    private void keepsInstrumentLocationExclusive() {
        check(RpmDisplayLocation.fromPersistedValue("left_speed")
                        == RpmDisplayLocation.LEFT_SPEED,
                "left placement restores as the only enum value");
        check(RpmDisplayLocation.fromPersistedValue("right_card")
                        == RpmDisplayLocation.RIGHT_CARD,
                "right placement restores as the only enum value");
        check(RpmDisplayLocation.fromPersistedValue("left_speed,right_card")
                        == RpmDisplayLocation.OFF,
                "combined placement is rejected");
        pass();
    }

    private void validatesStartupAnimationTimeline() {
        check(StartupMotion.contentAlpha(0f) == 0f, "startup begins dark");
        check(StartupMotion.contentAlpha(0.5f) == 1f, "startup content reaches full opacity");
        check(StartupMotion.contentAlpha(1f) == 0f, "startup overlay fades out");
        check(StartupMotion.bladeProgress(0.15f, 0) == 0f, "first DHT blade starts delayed");
        check(StartupMotion.bladeProgress(0.75f, 2) == 1f, "all DHT blades complete");
        check(StartupMotion.iconScale(0.6f) >= 0.99f, "startup icon reaches full scale");
        pass();
    }

    private static byte[] varintField(int field, long value) {
        return concat(varint(field << 3), varint(value));
    }
    private static byte[] bytesField(int field, byte[] value) {
        return concat(varint((field << 3) | 2), varint(value.length), value);
    }
    private static byte[] fixed32Field(int field, int value) {
        return concat(varint((field << 3) | 5), fixed32(value));
    }
    private static byte[] fixed32(int value) {
        return new byte[]{(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)};
    }
    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int current = (int) (value & 0x7f);
            value >>>= 7;
            out.write(value == 0 ? current : current | 0x80);
        } while (value != 0);
        return out.toByteArray();
    }
    private static byte[] concat(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) out.write(chunk, 0, chunk.length);
        return out.toByteArray();
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private void pass() { passed++; }
}
