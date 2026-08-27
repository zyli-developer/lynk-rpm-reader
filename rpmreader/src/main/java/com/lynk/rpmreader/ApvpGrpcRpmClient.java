package com.lynk.rpmreader;

import android.os.Process;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Iterator;
import java.util.List;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;

/** Reads an APVP engine-speed signal through a compatible local gRPC service. */
final class ApvpGrpcRpmClient implements AutoCloseable {
    interface FailureCallback { void onFailure(Throwable error); }

    private static final String TAG = "RpmReader";
    private static final String METHOD = "transfer_proto.TransferServer/readSignal";
    private static final ByteMarshaller MARSHALLER = new ByteMarshaller();
    private final VehicleRpmClient.Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ManagedChannel channel;
    private volatile Thread worker;

    ApvpGrpcRpmClient(VehicleRpmClient.Listener listener) {
        this.listener = listener;
    }

    void start(FailureCallback failureCallback) {
        closed.set(false);
        Thread thread = new Thread(() -> readLoop(failureCallback), "APVP-RPM");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    private void readLoop(FailureCallback failureCallback) {
        try {
            listener.onStatus("正在连接车辆 APVP 数据服务", false);
            channel = NettyChannelBuilder.forAddress("localhost", 40005).usePlaintext().build();
            String clientPid = Process.myPid() + "_rpm_monitor";
            Channel authenticated = ClientInterceptors.intercept(channel,
                    clientPidInterceptor(clientPid));
            MethodDescriptor<byte[], byte[]> readSignal = MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(METHOD)
                    .setRequestMarshaller(MARSHALLER)
                    .setResponseMarshaller(MARSHALLER)
                    .build();
            listener.onLog("APVP endpoint=localhost:40005, method=" + METHOD);
            Target target = activateTransfer();
            byte[] request = ApvpSignalCodec.encodeIdentify(target.identity.id, target.identity.name);
            String targetDescription = "transfer=" + target.transferId + "，signal_id="
                    + target.identity.id + " (0x" + Integer.toHexString(target.identity.id)
                    + ")，name=" + target.identity.name;
            listener.onLog("APVP 已按配置发现 " + targetDescription);
            Log.i(TAG, "APVP discovered " + targetDescription);

            int validReads = 0;
            int lastLoggedRpm = Integer.MIN_VALUE;
            while (!closed.get()) {
                byte[] response = ClientCalls.blockingUnaryCall(authenticated, readSignal,
                        CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS), request);
                ApvpSignalCodec.Reading reading = ApvpSignalCodec.decodeSignal(response);
                if (!ApvpSignalCodec.isEngineRpmReading(reading, target.identity)) {
                    throw new IOException("APVP returned unexpected signal id=" + reading.id
                            + " name=" + reading.name);
                }
                if (!Float.isFinite(reading.value) || reading.value < 0f) {
                    throw new IOException("APVP RPM unavailable: " + reading.value);
                }
                int rpm = CarRpmValue.toDisplayRpm(reading.value);
                listener.onRpm(rpm, reading.mode);
                if (validReads++ == 0) {
                    listener.onStatus("已连接车辆 APVP 发动机转速", false);
                    listener.onLog("APVP client_pid=" + clientPid + "，已收到有效数据，signal_id="
                            + reading.id + " (0x" + Integer.toHexString(reading.id) + ")");
                    Log.i(TAG, "APVP first response signal_id=" + reading.id + " (0x"
                            + Integer.toHexString(reading.id) + "), name=" + reading.name
                            + ", mode=" + reading.mode + ", value=" + reading.value);
                }
                if (rpm != lastLoggedRpm) {
                    Log.i(TAG, "APVP EngNSafeEngN=" + reading.value + " rpm, mode=" + reading.mode);
                    lastLoggedRpm = rpm;
                }
                Thread.sleep(100L);
            }
        } catch (Throwable error) {
            if (!closed.get()) {
                Log.w(TAG, "APVP RPM path failed", error);
                failureCallback.onFailure(error);
            }
        }
    }

    private Target activateTransfer() throws IOException {
        ManagedChannel debugChannel = null;
        try {
            listener.onStatus("正在激活发动机转速数据传输", false);
            debugChannel = NettyChannelBuilder.forAddress("localhost", 40007).usePlaintext().build();
            Channel debug = ClientInterceptors.intercept(debugChannel,
                    clientPidInterceptor(Process.myPid() + "_rpm_monitor_debug"));
            MethodDescriptor<byte[], byte[]> getAll = method(
                    "transfer_proto.TransferDebugServer/getAllTransfer",
                    MethodDescriptor.MethodType.UNARY);
            byte[] allResponse = ClientCalls.blockingUnaryCall(debug, getAll,
                    CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS), new byte[0]);
            List<Long> transferIds = ApvpSignalCodec.decodeTransferIds(allResponse);
            listener.onLog("APVP debug：发现 " + transferIds.size() + " 个 transfer");
            Log.i(TAG, "APVP transfer IDs=" + transferIds);

            MethodDescriptor<byte[], byte[]> getConfigs = method(
                    "transfer_proto.TransferDebugServer/getTransferSignalConfig",
                    MethodDescriptor.MethodType.SERVER_STREAMING);
            Long matched = null;
            ApvpSignalCodec.SignalIdentity matchedIdentity = null;
            for (long transferId : transferIds) {
                Iterator<byte[]> configs = ClientCalls.blockingServerStreamingCall(debug, getConfigs,
                        CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS),
                        ApvpSignalCodec.encodeInt64(transferId));
                while (configs.hasNext()) {
                    ApvpSignalCodec.SignalIdentity identity =
                            ApvpSignalCodec.decodeSignalConfigIdentity(configs.next());
                    if (ApvpSignalCodec.isEngineRpmConfig(identity)) {
                        matched = transferId;
                        matchedIdentity = identity;
                        break;
                    }
                }
                if (matched != null) break;
            }
            if (matched == null || matchedIdentity == null) {
                throw new IOException("APVP transfer config does not contain EngNSafeEngN");
            }
            MethodDescriptor<byte[], byte[]> setReady = method(
                    "transfer_proto.TransferDebugServer/setReady",
                    MethodDescriptor.MethodType.UNARY);
            ClientCalls.blockingUnaryCall(debug, setReady,
                    CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS),
                    ApvpSignalCodec.encodeInt64(matched));
            listener.onLog("APVP transfer " + matched + " 已执行 setReady");
            Log.i(TAG, "APVP setReady transfer=" + matched + ", signal_id="
                    + matchedIdentity.id + " (0x" + Integer.toHexString(matchedIdentity.id)
                    + "), name=" + matchedIdentity.name);
            return new Target(matched, matchedIdentity);
        } finally {
            if (debugChannel != null) debugChannel.shutdownNow();
        }
    }

    private static final class Target {
        final long transferId;
        final ApvpSignalCodec.SignalIdentity identity;

        Target(long transferId, ApvpSignalCodec.SignalIdentity identity) {
            this.transferId = transferId;
            this.identity = identity;
        }
    }

    private static MethodDescriptor<byte[], byte[]> method(
            String name, MethodDescriptor.MethodType type) {
        return MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(type).setFullMethodName(name)
                .setRequestMarshaller(MARSHALLER).setResponseMarshaller(MARSHALLER).build();
    }

    private static ClientInterceptor clientPidInterceptor(String value) {
        return new ClientInterceptor() {
            @Override public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                        next.newCall(method, options)) {
                    @Override public void start(Listener<RespT> listener, Metadata headers) {
                        headers.put(Metadata.Key.of("client_pid", Metadata.ASCII_STRING_MARSHALLER), value);
                        super.start(listener, headers);
                    }
                };
            }
        };
    }

    @Override public void close() {
        closed.set(true);
        Thread current = worker;
        worker = null;
        if (current != null) current.interrupt();
        ManagedChannel currentChannel = channel;
        channel = null;
        if (currentChannel != null) currentChannel.shutdownNow();
    }

    private static final class ByteMarshaller implements MethodDescriptor.Marshaller<byte[]> {
        @Override public InputStream stream(byte[] value) { return new ByteArrayInputStream(value); }
        @Override public byte[] parse(InputStream input) {
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                return output.toByteArray();
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }
    }
}
