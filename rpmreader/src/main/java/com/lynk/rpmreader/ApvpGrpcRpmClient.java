package com.lynk.rpmreader;

import android.os.Process;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;

/** Reads engine speed from the local APVP service using runtime signal discovery. */
final class ApvpGrpcRpmClient implements AutoCloseable {
    interface FailureCallback { void onFailure(Throwable error); }

    private static final String TAG = "RpmReader";
    private static final String READ_METHOD = "transfer_proto.TransferServer/readSignal";
    private static final String STREAM_METHOD =
            "transfer_proto.TransferServer/listenerSignalStream";
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
            Channel authenticated = ClientInterceptors.intercept(
                    channel, clientPidInterceptor(clientPid));
            ResolvedSignal target = activateTransfer();
            listener.onLog("APVP 动态发现 " + target.config.name
                    + ": id=" + target.config.id
                    + " (0x" + Integer.toHexString(target.config.id) + ")"
                    + ", module=" + target.config.module
                    + ", valueType=0x" + Integer.toHexString(target.config.valueType));

            try {
                streamSignals(authenticated, target, clientPid);
            } catch (Throwable streamError) {
                if (closed.get()) return;
                listener.onLog("APVP 流式监听不可用，自动回退 readSignal："
                        + streamError.getClass().getSimpleName() + ": "
                        + String.valueOf(streamError.getMessage()));
                Log.w(TAG, "APVP stream unavailable; falling back to unary polling", streamError);
                pollSignals(authenticated, target, clientPid);
            }
        } catch (Throwable error) {
            if (!closed.get()) {
                Log.w(TAG, "APVP RPM path failed", error);
                failureCallback.onFailure(error);
            }
        }
    }

    private void streamSignals(Channel authenticated, ResolvedSignal target, String clientPid)
            throws Exception {
        listener.onLog("APVP endpoint=localhost:40005, method=" + STREAM_METHOD);
        listener.onStatus("正在订阅发动机转速", false);
        MethodDescriptor<byte[], byte[]> streamMethod = method(
                STREAM_METHOD, MethodDescriptor.MethodType.BIDI_STREAMING);
        CountDownLatch firstValid = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicReference<Throwable> streamFailure = new AtomicReference<>();
        AtomicReference<ClientCall<byte[], byte[]>> callReference = new AtomicReference<>();
        ReadingState state = new ReadingState();

        ClientCall<byte[], byte[]> call = authenticated.newCall(streamMethod, CallOptions.DEFAULT);
        callReference.set(call);
        call.start(new ClientCall.Listener<byte[]>() {
            @Override public void onMessage(byte[] message) {
                try {
                    for (ApvpSignalCodec.Reading reading
                            : ApvpSignalCodec.decodeSignalBatch(message)) {
                        if (ApvpSignalCodec.matches(reading, target.config)) {
                            publishReading(target, reading, state, clientPid, "stream");
                            firstValid.countDown();
                        }
                    }
                    ClientCall<byte[], byte[]> active = callReference.get();
                    if (active != null) active.request(1);
                } catch (Throwable error) {
                    streamFailure.compareAndSet(null, error);
                    ClientCall<byte[], byte[]> active = callReference.get();
                    if (active != null) active.cancel("invalid APVP stream response", error);
                }
            }

            @Override public void onClose(Status status, Metadata trailers) {
                if (!status.isOk() && !closed.get()) {
                    streamFailure.compareAndSet(null,
                            new IOException("APVP stream closed: " + status));
                }
                terminal.countDown();
            }
        }, new Metadata());
        call.request(1);
        call.sendMessage(ApvpSignalCodec.encodeListenerRequest(
                target.config.id, target.config.name));

        try {
            if (!firstValid.await(3, TimeUnit.SECONDS)) {
                Throwable failure = streamFailure.get();
                if (failure != null) throw asException(failure);
                throw new IOException("APVP stream produced no matching RPM signal within 3 seconds");
            }
            while (!closed.get()) {
                if (terminal.await(1, TimeUnit.SECONDS)) {
                    Throwable failure = streamFailure.get();
                    if (failure != null) throw asException(failure);
                    throw new IOException("APVP stream completed unexpectedly");
                }
            }
        } finally {
            callReference.set(null);
            call.cancel("RPM reader stopped or switching transport", null);
        }
    }

    private void pollSignals(Channel authenticated, ResolvedSignal target, String clientPid)
            throws Exception {
        MethodDescriptor<byte[], byte[]> readSignal = method(
                READ_METHOD, MethodDescriptor.MethodType.UNARY);
        byte[] request = ApvpSignalCodec.encodeIdentify(target.config.id, target.config.name);
        ReadingState state = new ReadingState();
        listener.onLog("APVP endpoint=localhost:40005, method=" + READ_METHOD);
        listener.onStatus("正在轮询发动机转速", false);
        while (!closed.get()) {
            byte[] response = ClientCalls.blockingUnaryCall(authenticated, readSignal,
                    CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS), request);
            ApvpSignalCodec.Reading reading = ApvpSignalCodec.decodeSignal(response);
            publishReading(target, reading, state, clientPid, "poll");
            Thread.sleep(100L);
        }
    }

    private void publishReading(ResolvedSignal target, ApvpSignalCodec.Reading reading,
            ReadingState state, String clientPid, String transport) throws IOException {
        if (!ApvpSignalCodec.matches(reading, target.config)) {
            throw new IOException("APVP returned unexpected signal id=" + reading.id
                    + " name=" + reading.name);
        }
        if (!Float.isFinite(reading.value) || reading.value < 0f) {
            throw new IOException("APVP RPM unavailable: " + reading.value);
        }
        int rpm = CarRpmValue.toDisplayRpm(reading.value);
        listener.onRpm(rpm, reading.mode);
        if (state.validReads++ == 0) {
            listener.onStatus("已连接车辆 APVP 发动机转速", false);
            listener.onLog("APVP client_pid=" + clientPid + "，已收到有效数据，transport="
                    + transport + ", signal_id=" + reading.id);
        }
        if (rpm != state.lastLoggedRpm) {
            Log.i(TAG, "APVP " + target.config.name + "=" + reading.value
                    + " rpm, mode=" + reading.mode + ", transport=" + transport);
            state.lastLoggedRpm = rpm;
        }
    }

    private ResolvedSignal activateTransfer() throws IOException {
        ManagedChannel debugChannel = null;
        try {
            listener.onStatus("正在发现并激活发动机转速信号", false);
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

            MethodDescriptor<byte[], byte[]> getConfigs = method(
                    "transfer_proto.TransferDebugServer/getTransferSignalConfig",
                    MethodDescriptor.MethodType.SERVER_STREAMING);
            ResolvedSignal matched = null;
            for (long transferId : transferIds) {
                Iterator<byte[]> configs = ClientCalls.blockingServerStreamingCall(debug, getConfigs,
                        CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS),
                        ApvpSignalCodec.encodeInt64(transferId));
                while (configs.hasNext()) {
                    ApvpSignalCodec.SignalConfig config =
                            ApvpSignalCodec.decodeSignalConfig(configs.next());
                    if (!ApvpSignalCodec.isEngineRpmConfig(config)) continue;
                    if (matched != null
                            && (matched.transferId != transferId
                                || matched.config.id != config.id)) {
                        throw new IOException("APVP contains ambiguous "
                                + ApvpSignalCodec.ENGINE_RPM_NAME + " configs");
                    }
                    matched = new ResolvedSignal(transferId, config);
                }
            }
            if (matched == null) {
                throw new IOException("APVP transfer config does not contain a readable "
                        + ApvpSignalCodec.ENGINE_RPM_NAME);
            }
            MethodDescriptor<byte[], byte[]> setReady = method(
                    "transfer_proto.TransferDebugServer/setReady",
                    MethodDescriptor.MethodType.UNARY);
            ClientCalls.blockingUnaryCall(debug, setReady,
                    CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS),
                    ApvpSignalCodec.encodeInt64(matched.transferId));
            listener.onLog("APVP transfer " + matched.transferId + " 已执行 setReady");
            return matched;
        } finally {
            if (debugChannel != null) debugChannel.shutdownNow();
        }
    }

    private static MethodDescriptor<byte[], byte[]> method(
            String name, MethodDescriptor.MethodType type) {
        return MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(type).setFullMethodName(name)
                .setRequestMarshaller(MARSHALLER).setResponseMarshaller(MARSHALLER).build();
    }

    private static Exception asException(Throwable error) {
        return error instanceof Exception
                ? (Exception) error
                : new IOException("APVP stream failed", error);
    }

    private static ClientInterceptor clientPidInterceptor(String value) {
        return new ClientInterceptor() {
            @Override public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                        next.newCall(method, options)) {
                    @Override public void start(Listener<RespT> listener, Metadata headers) {
                        headers.put(Metadata.Key.of(
                                "client_pid", Metadata.ASCII_STRING_MARSHALLER), value);
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

    private static final class ResolvedSignal {
        final long transferId;
        final ApvpSignalCodec.SignalConfig config;

        ResolvedSignal(long transferId, ApvpSignalCodec.SignalConfig config) {
            this.transferId = transferId;
            this.config = config;
        }
    }

    private static final class ReadingState {
        int validReads;
        int lastLoggedRpm = Integer.MIN_VALUE;
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
