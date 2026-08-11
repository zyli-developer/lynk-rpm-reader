package io.github.zylideveloper.rpmreader;

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
        decodesApvpUnpackedFloat();
        decodesApvpPackedFloat();
        rejectsApvpResponseWithoutFloat();
        decodesApvpTransferIdsAndConfig();
        mapsGaugeMovement();
        clampsGaugeRange();
        usesConfiguredPowerPeakScale();
        mapsEngineCharacteristicZones();
        formatsAnimatedGaugeReadouts();
        validatesStartupAnimationTimeline();
        System.out.println("RPM logic tests passed: " + passed + "/13");
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
        byte[] request = ApvpSignalCodec.encodeIdentify(308282774, "EngNSafeEngN");
        check(ProtoReader.firstInt32(request, 1, 0) == 308282774, "request signal id");
        check("EngNSafeEngN".equals(new String(ProtoReader.firstBytes(request, 2), "UTF-8")),
                "request signal name");
        pass();
    }

    private void decodesApvpUnpackedFloat() throws Exception {
        byte[] identify = ApvpSignalCodec.encodeIdentify(308282774, "EngNSafeEngN");
        ApvpSignalCodec.Reading reading = ApvpSignalCodec.decodeSignal(concat(
                bytesField(1, identify), varintField(2, 1),
                fixed32Field(5, Float.floatToIntBits(1666.5f))));
        check(reading.id == 308282774 && "EngNSafeEngN".equals(reading.name), "response identity");
        check(reading.value == 1666.5f && reading.mode == 1, "unpacked Float and mode");
        pass();
    }

    private void decodesApvpPackedFloat() throws Exception {
        ApvpSignalCodec.Reading reading = ApvpSignalCodec.decodeSignal(
                bytesField(5, fixed32(Float.floatToIntBits(875.25f))));
        check(reading.value == 875.25f, "packed Float");
        pass();
    }

    private void rejectsApvpResponseWithoutFloat() {
        try {
            ApvpSignalCodec.decodeSignal(varintField(2, 1));
            throw new AssertionError("response without Float must fail");
        } catch (IOException expected) { pass(); }
    }

    private void decodesApvpTransferIdsAndConfig() throws Exception {
        byte[] first = concat(varintField(1, 41), bytesField(2, "first".getBytes("UTF-8")));
        List<Long> ids = ApvpSignalCodec.decodeTransferIds(concat(
                bytesField(1, first), bytesField(1, varintField(1, 99))));
        check(ids.size() == 2 && ids.get(0) == 41L && ids.get(1) == 99L, "transfer IDs");
        byte[] config = bytesField(1, ApvpSignalCodec.encodeIdentify(308282774, "EngNSafeEngN"));
        check(ApvpSignalCodec.isSignalConfig(config, 308282774, "EngNSafeEngN"), "RPM config");
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
